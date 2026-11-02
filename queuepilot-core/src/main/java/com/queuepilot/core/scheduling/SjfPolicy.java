package com.queuepilot.core.scheduling;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Shortest Job First (non-preemptive): smallest declared estimate first, then FCFS.
 * Uses the estimate, not the real runtime, which is unknown until the job has run.
 */
public class SjfPolicy implements SchedulingPolicy {

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
