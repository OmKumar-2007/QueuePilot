package com.queuepilot.core.scheduling;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Priority scheduling with aging: a job's effective priority grows by {@code agingStep} for every
 * {@code agingInterval} it has waited, so low-priority jobs cannot starve forever.
 *
 * <pre>effective = priority + floor(waited / agingInterval) * agingStep</pre>
 *
 * Highest effective priority first; ties go to the earlier submission.
 */
public class PriorityAgingPolicy implements SchedulingPolicy {

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
