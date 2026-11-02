package com.queuepilot.benchmarks.simulation;

import com.queuepilot.core.metrics.JobTiming;
import com.queuepilot.core.metrics.MetricsCalculator;
import com.queuepilot.core.metrics.ScheduleMetrics;
import com.queuepilot.core.scheduling.SchedulableJob;
import com.queuepilot.core.scheduling.SchedulingPolicy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Discrete-event simulation of non-preemptive scheduling on N identical workers with a virtual
 * clock. Whenever a worker becomes free, the jobs that have arrived are ordered by the <b>same
 * {@link SchedulingPolicy} code the coordinator uses</b> and the first one starts.
 */
public final class Simulator {

    private static final Instant EPOCH = Instant.EPOCH;

    private Simulator() {
    }

    public record Run(String policy, ScheduleMetrics metrics, List<Record> records) {
    }

    public record Record(String jobId, int priority, long durationMs, long arrivalMs, long startMs, long finishMs) {
    }

    public static Run run(List<SimJob> workload, SchedulingPolicy policy, int workers) {
        List<SimJob> pending = new ArrayList<>(workload);
        pending.sort(Comparator.comparingLong(SimJob::arrivalMs).thenComparing(SimJob::id));
        Map<String, SimJob> ready = new HashMap<>();
        PriorityQueue<Long> workerFreeAt = new PriorityQueue<>();
        for (int i = 0; i < workers; i++) {
            workerFreeAt.add(0L);
        }

        List<Record> records = new ArrayList<>();
        int next = 0;
        long now = 0;
        while (records.size() < workload.size()) {
            now = Math.max(now, workerFreeAt.poll());
            if (ready.isEmpty() && next < pending.size()) {
                now = Math.max(now, pending.get(next).arrivalMs());
            }
            while (next < pending.size() && pending.get(next).arrivalMs() <= now) {
                SimJob job = pending.get(next++);
                ready.put(job.id(), job);
            }
            List<SchedulableJob> candidates = ready.values().stream()
                    .map(job -> new SchedulableJob(job.id(), "default", job.priority(), job.durationMs(),
                            EPOCH.plusMillis(job.arrivalMs())))
                    .toList();
            SimJob chosen = ready.remove(policy.order(candidates, EPOCH.plusMillis(now)).get(0).id());
            long finish = now + chosen.durationMs();
            records.add(new Record(chosen.id(), chosen.priority(), chosen.durationMs(), chosen.arrivalMs(), now, finish));
            workerFreeAt.add(finish);
        }

        List<JobTiming> timings = records.stream()
                .map(r -> new JobTiming(EPOCH.plusMillis(r.arrivalMs()), EPOCH.plusMillis(r.startMs()), EPOCH.plusMillis(r.finishMs())))
                .toList();
        return new Run(policy.type().name(), MetricsCalculator.compute(timings, workers), records);
    }
}
