package com.queuepilot.core.scheduling;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/** First Come, First Served: earliest submission first; id breaks ties so the order is deterministic. */
public class FcfsPolicy implements SchedulingPolicy {

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
