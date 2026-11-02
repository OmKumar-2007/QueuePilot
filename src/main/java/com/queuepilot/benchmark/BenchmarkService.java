package com.queuepilot.benchmark;

import com.queuepilot.dto.BenchmarkResultDto;
import com.queuepilot.model.Job;
import com.queuepilot.scheduler.FcFSSchedulingPolicy;
import com.queuepilot.scheduler.MlfqSchedulingPolicy;
import com.queuepilot.scheduler.PriorityAgingSchedulingPolicy;
import com.queuepilot.scheduler.SchedulingPolicy;
import com.queuepilot.scheduler.SjfSchedulingPolicy;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Computes deterministic comparative benchmark metrics across scheduling algorithms
 * on identical workloads.
 * Satisfies SRS F15 and Demo Scenario Step 7.
 */
@Service
public class BenchmarkService {

    public List<BenchmarkResultDto> runComparativeBenchmark() {
        List<SchedulingPolicy> policies = List.of(
                new FcFSSchedulingPolicy(),
                new SjfSchedulingPolicy(),
                new PriorityAgingSchedulingPolicy(2, 1),
                new MlfqSchedulingPolicy(5000L, 15000L, 10L)
        );

        List<BenchmarkResultDto> results = new ArrayList<>();
        for (SchedulingPolicy policy : policies) {
            results.add(simulateWorkload(policy, DemoWorkloadService.createStandard10Jobs(), 4));
        }
        return results;
    }

    /**
     * Discrete-event simulation of workload execution on N concurrent workers.
     */
    public BenchmarkResultDto simulateWorkload(SchedulingPolicy policy, List<Job> originalJobs, int workerCount) {
        Instant baseTime = Instant.ofEpochMilli(1_000_000_000_000L); // Fixed simulated epoch

        // Clone jobs with fresh IDs and preserve dependencies
        Map<String, Job> simulatedJobs = new HashMap<>();
        for (Job j : originalJobs) {
            simulatedJobs.put(j.getId(), j);
        }

        Set<String> completedJobIds = new HashSet<>();
        Set<String> remainingJobIds = new HashSet<>(simulatedJobs.keySet());

        // Worker free timestamps (min-heap)
        PriorityQueue<Long> workerAvailableAtMs = new PriorityQueue<>();
        for (int i = 0; i < workerCount; i++) {
            workerAvailableAtMs.add(0L);
        }

        long currentClockMs = 0L;
        long totalWaitTimeMs = 0L;
        long totalTurnaroundTimeMs = 0L;
        long sumJobExecutionTimeMs = 0L;

        while (!remainingJobIds.isEmpty()) {
            // Find all ready jobs whose dependencies have completed
            List<Job> readyJobs = new ArrayList<>();
            for (String id : remainingJobIds) {
                Job job = simulatedJobs.get(id);
                if (completedJobIds.containsAll(job.getDependencies())) {
                    readyJobs.add(job);
                }
            }

            if (readyJobs.isEmpty()) {
                // Advance clock to when next busy worker finishes
                currentClockMs = workerAvailableAtMs.peek();
                continue;
            }

            // Next available worker
            long nextWorkerFreeMs = workerAvailableAtMs.poll();
            currentClockMs = Math.max(currentClockMs, nextWorkerFreeMs);

            // Policy orders ready jobs at current clock time
            Instant currentSimInstant = baseTime.plusMillis(currentClockMs);
            List<Job> orderedJobs = policy.orderJobs(readyJobs, currentSimInstant);

            Job selectedJob = orderedJobs.get(0);
            long jobDuration = selectedJob.getEstimatedDurationMs();
            sumJobExecutionTimeMs += jobDuration;

            long startMs = currentClockMs;
            long finishMs = startMs + jobDuration;

            long waitTimeMs = startMs;
            long turnaroundTimeMs = finishMs;

            totalWaitTimeMs += waitTimeMs;
            totalTurnaroundTimeMs += turnaroundTimeMs;

            completedJobIds.add(selectedJob.getId());
            remainingJobIds.remove(selectedJob.getId());

            workerAvailableAtMs.add(finishMs);
        }

        // Makespan = maximum completion time
        long makespanMs = 0L;
        while (!workerAvailableAtMs.isEmpty()) {
            makespanMs = Math.max(makespanMs, workerAvailableAtMs.poll());
        }

        int jobCount = originalJobs.size();
        double avgWaitTime = (double) totalWaitTimeMs / jobCount;
        double avgTurnaround = (double) totalTurnaroundTimeMs / jobCount;
        double makespanSeconds = Math.max(0.001, makespanMs / 1000.0);
        double throughput = jobCount / makespanSeconds;
        double utilization = (double) sumJobExecutionTimeMs / (workerCount * makespanMs) * 100.0;

        return new BenchmarkResultDto(
                policy.getPolicyName(),
                jobCount,
                Math.round(avgWaitTime * 10.0) / 10.0,
                Math.round(avgTurnaround * 10.0) / 10.0,
                makespanMs,
                Math.round(throughput * 100.0) / 100.0,
                Math.round(utilization * 10.0) / 10.0
        );
    }
}
