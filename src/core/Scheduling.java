package core;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Scheduling policies, queue fairness and the metrics used to compare them. */
public final class Scheduling {

    private Scheduling() {
    }

    /**
     * Everything a scheduling policy is allowed to know about a job. Deliberately contains nothing
     * task-specific: the scheduler never knows what a job does.
     *
     * @param priority    1 (lowest) .. 10 (highest)
     * @param estimatedMs declared execution estimate, used by SJF
     * @param submittedAt arrival time, used by FCFS and for aging
     */
    public record SchedulableJob(String id, String queue, int priority, long estimatedMs, Instant submittedAt) {
    }

    public enum PolicyType {
        FCFS("First Come, First Served"),
        PRIORITY_AGING("Priority with Aging"),
        SJF("Shortest Job First");

        private final String displayName;

        PolicyType(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }

        /** Creates the policy; aging settings are ignored by policies that do not age. */
        public SchedulingPolicy create(Duration agingInterval, int agingStep) {
            return switch (this) {
                case FCFS -> new FcfsPolicy();
                case PRIORITY_AGING -> new PriorityAgingPolicy(agingInterval, agingStep);
                case SJF -> new SjfPolicy();
            };
        }
    }

    /**
     * Strategy for ordering runnable jobs. Implementations are pure functions of their input and
     * {@code now}: no database, no clock, no side effects. That lets the coordinator and the
     * benchmark simulator use exactly the same code.
     */
    public interface SchedulingPolicy {

        PolicyType type();

        /** Returns the jobs in the order they should be dispatched (first = next). */
        List<SchedulableJob> order(Collection<SchedulableJob> jobs, Instant now);
    }

    /** First Come, First Served: earliest submission first; id breaks ties so the order is deterministic. */
    public static class FcfsPolicy implements SchedulingPolicy {

        private static final Comparator<SchedulableJob> ORDER =
                Comparator.comparing(SchedulableJob::submittedAt).thenComparing(SchedulableJob::id);

        @Override
        public PolicyType type() {
            return PolicyType.FCFS;
        }

        @Override
        public List<SchedulableJob> order(Collection<SchedulableJob> jobs, Instant now) {
            return jobs.stream().sorted(ORDER).toList();
        }
    }

    /**
     * Shortest Job First (non-preemptive): smallest declared estimate first, then FCFS.
     * Uses the estimate, not the real runtime, which is unknown until the job has run.
     */
    public static class SjfPolicy implements SchedulingPolicy {

        private static final Comparator<SchedulableJob> ORDER =
                Comparator.comparingLong(SchedulableJob::estimatedMs)
                        .thenComparing(SchedulableJob::submittedAt)
                        .thenComparing(SchedulableJob::id);

        @Override
        public PolicyType type() {
            return PolicyType.SJF;
        }

        @Override
        public List<SchedulableJob> order(Collection<SchedulableJob> jobs, Instant now) {
            return jobs.stream().sorted(ORDER).toList();
        }
    }

    /**
     * Priority scheduling with aging: a job's effective priority grows by {@code agingStep} for every
     * {@code agingInterval} it has waited, so low-priority jobs cannot starve forever.
     *
     * <pre>effective = priority + floor(waited / agingInterval) * agingStep</pre>
     *
     * Highest effective priority first; ties go to the earlier submission.
     */
    public static class PriorityAgingPolicy implements SchedulingPolicy {

        private final Duration agingInterval;
        private final int agingStep;

        public PriorityAgingPolicy(Duration agingInterval, int agingStep) {
            if (agingInterval.isZero() || agingInterval.isNegative() || agingStep < 0) {
                throw new IllegalArgumentException("Aging interval must be positive and step non-negative");
            }
            this.agingInterval = agingInterval;
            this.agingStep = agingStep;
        }

        @Override
        public PolicyType type() {
            return PolicyType.PRIORITY_AGING;
        }

        public long effectivePriority(SchedulableJob job, Instant now) {
            long waitedMs = Math.max(0, Duration.between(job.submittedAt(), now).toMillis());
            return job.priority() + (waitedMs / agingInterval.toMillis()) * agingStep;
        }

        @Override
        public List<SchedulableJob> order(Collection<SchedulableJob> jobs, Instant now) {
            Comparator<SchedulableJob> byEffectivePriority =
                    Comparator.comparingLong((SchedulableJob job) -> effectivePriority(job, now)).reversed();
            return jobs.stream()
                    .sorted(byEffectivePriority
                            .thenComparing(SchedulableJob::submittedAt)
                            .thenComparing(SchedulableJob::id))
                    .toList();
        }
    }

    /**
     * Round-robin fairness across logical queues (F14).
     *
     * <p>Jobs are grouped by queue; queues are visited in name order starting with the queue after
     * the one served last; inside each queue the active policy decides the order. The result is one
     * flat candidate list: the scheduler claims the first candidate that a worker can take and then
     * calls {@link #markServed} so the next decision starts with the following queue.</p>
     */
    public static class QueueRotation {

        private String lastServedQueue;

        public synchronized List<SchedulableJob> arrange(Collection<SchedulableJob> jobs, SchedulingPolicy policy, Instant now) {
            Map<String, List<SchedulableJob>> byQueue = new TreeMap<>();
            for (SchedulableJob job : jobs) {
                byQueue.computeIfAbsent(job.queue(), queue -> new ArrayList<>()).add(job);
            }

            List<SchedulableJob> arranged = new ArrayList<>(jobs.size());
            for (String queue : rotationOrder(new ArrayList<>(byQueue.keySet()))) {
                arranged.addAll(policy.order(byQueue.get(queue), now));
            }
            return arranged;
        }

        public synchronized void markServed(String queue) {
            lastServedQueue = queue;
        }

        public synchronized String lastServedQueue() {
            return lastServedQueue;
        }

        /** Sorted queue names rotated so that the first queue after {@code lastServedQueue} comes first. */
        private List<String> rotationOrder(List<String> sortedQueues) {
            if (lastServedQueue == null) {
                return sortedQueues;
            }
            int start = 0;
            while (start < sortedQueues.size() && sortedQueues.get(start).compareTo(lastServedQueue) <= 0) {
                start++;
            }
            List<String> rotated = new ArrayList<>(sortedQueues.subList(start, sortedQueues.size()));
            rotated.addAll(sortedQueues.subList(0, start));
            return rotated;
        }
    }

    /** The three timestamps every scheduling metric is derived from. */
    public record JobTiming(Instant submittedAt, Instant startedAt, Instant finishedAt) {

        public long waitMs() {
            return Duration.between(submittedAt, startedAt).toMillis();
        }

        public long turnaroundMs() {
            return Duration.between(submittedAt, finishedAt).toMillis();
        }

        public long executionMs() {
            return Duration.between(startedAt, finishedAt).toMillis();
        }
    }

    /**
     * Standard scheduling metrics for a set of finished jobs.
     *
     * @param maxWaitMs   the longest any job waited: the starvation indicator
     * @param makespanMs  first submission to last completion
     * @param utilization busy worker time / (slots * makespan), 0..1
     */
    public record ScheduleMetrics(int jobCount,
                                  double avgWaitMs,
                                  double avgTurnaroundMs,
                                  double p95WaitMs,
                                  double maxWaitMs,
                                  double makespanMs,
                                  double throughputPerSec,
                                  double utilization) {
    }

    /** Summary statistics of a sample (median and p95 use nearest-rank percentiles). */
    public record Stats(int count, double mean, double median, double p95, double min, double max) {

        public static Stats of(Collection<? extends Number> values) {
            if (values.isEmpty()) {
                return new Stats(0, 0, 0, 0, 0, 0);
            }
            List<Double> sorted = new ArrayList<>();
            double sum = 0;
            for (Number value : values) {
                sorted.add(value.doubleValue());
                sum += value.doubleValue();
            }
            Collections.sort(sorted);
            return new Stats(sorted.size(), sum / sorted.size(), percentile(sorted, 50), percentile(sorted, 95),
                    sorted.get(0), sorted.get(sorted.size() - 1));
        }

        /** Nearest-rank percentile of an already sorted list. */
        public static double percentile(List<Double> sorted, double percent) {
            if (sorted.isEmpty()) {
                return 0;
            }
            int rank = (int) Math.ceil(percent / 100.0 * sorted.size());
            return sorted.get(Math.max(0, Math.min(sorted.size() - 1, rank - 1)));
        }
    }

    /**
     * Computes {@link ScheduleMetrics}. Shared by the coordinator's live dashboard and the benchmark
     * module so both report the same definitions:
     * <ul>
     *   <li>waiting time = start - submission</li>
     *   <li>turnaround time = completion - submission</li>
     *   <li>throughput = jobs / makespan</li>
     *   <li>utilization = sum(execution time) / (slots * makespan)</li>
     * </ul>
     */
    public static final class MetricsCalculator {

        private MetricsCalculator() {
        }

        public static ScheduleMetrics compute(List<JobTiming> timings, int slots) {
            if (timings.isEmpty()) {
                return new ScheduleMetrics(0, 0, 0, 0, 0, 0, 0, 0);
            }
            List<Long> waits = new ArrayList<>();
            double totalTurnaround = 0;
            double busyMs = 0;
            Instant firstSubmit = timings.get(0).submittedAt();
            Instant lastFinish = timings.get(0).finishedAt();
            for (JobTiming timing : timings) {
                waits.add(timing.waitMs());
                totalTurnaround += timing.turnaroundMs();
                busyMs += timing.executionMs();
                if (timing.submittedAt().isBefore(firstSubmit)) {
                    firstSubmit = timing.submittedAt();
                }
                if (timing.finishedAt().isAfter(lastFinish)) {
                    lastFinish = timing.finishedAt();
                }
            }
            Stats waitStats = Stats.of(waits);
            double makespanMs = Math.max(1, Duration.between(firstSubmit, lastFinish).toMillis());
            double utilization = slots > 0 ? Math.min(1.0, busyMs / (slots * makespanMs)) : 0;
            return new ScheduleMetrics(timings.size(), waitStats.mean(), totalTurnaround / timings.size(),
                    waitStats.p95(), waitStats.max(), makespanMs, timings.size() / (makespanMs / 1000.0), utilization);
        }
    }
}
