package com.queuepilot.core.metrics;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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
public final class MetricsCalculator {

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
