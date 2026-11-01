package com.queuepilot.scheduler;

import com.queuepilot.model.Job;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Multi-Level Feedback Queue (MLFQ) Scheduling Policy.
 * Employs multiple priority tiers with dynamic demotion and anti-starvation priority boosting.
 *
 * Tier 0 (High Priority): Short interactive tasks (<= 5s)
 * Tier 1 (Medium Priority): Medium computational tasks (5s - 15s)
 * Tier 2 (Low Priority): Long batch compute tasks (> 15s)
 *
 * Anti-starvation: All waiting jobs are periodically boosted to Tier 0.
 */
@Component("MLFQ")
public class MlfqSchedulingPolicy implements SchedulingPolicy {

    private final long level0ThresholdMs;
    private final long level1ThresholdMs;
    private final long boostIntervalSeconds;

    private volatile Instant lastBoostTime = Instant.now();

    public MlfqSchedulingPolicy(
            @Value("${queuepilot.scheduler.mlfq.level0-threshold-ms:5000}") long level0ThresholdMs,
            @Value("${queuepilot.scheduler.mlfq.level1-threshold-ms:15000}") long level1ThresholdMs,
            @Value("${queuepilot.scheduler.mlfq.boost-interval-seconds:20}") long boostIntervalSeconds) {
        this.level0ThresholdMs = Math.max(500L, level0ThresholdMs);
        this.level1ThresholdMs = Math.max(level0ThresholdMs, level1ThresholdMs);
        this.boostIntervalSeconds = Math.max(2L, boostIntervalSeconds);
    }

    public MlfqSchedulingPolicy() {
        this(5000L, 15000L, 20L);
    }

    @Override
    public String getPolicyName() {
        return "MLFQ";
    }

    @Override
    public List<Job> orderJobs(List<Job> candidateJobs, Instant now) {
        if (candidateJobs == null || candidateJobs.isEmpty()) {
            return List.of();
        }

        List<Job> sorted = new ArrayList<>(candidateJobs);

        // Check Anti-Starvation Rule: Periodic Priority Boost to Level 0
        boolean shouldBoost = Duration.between(lastBoostTime, now).getSeconds() >= boostIntervalSeconds;
        if (shouldBoost) {
            for (Job job : sorted) {
                job.setMlfqLevel(0);
            }
            this.lastBoostTime = now;
        } else {
            // Apply MLFQ placement and demotion rules based on workload duration
            for (Job job : sorted) {
                long duration = job.getEstimatedDurationMs();
                if (duration <= level0ThresholdMs) {
                    job.setMlfqLevel(0);
                } else if (duration <= level1ThresholdMs) {
                    job.setMlfqLevel(1);
                } else {
                    job.setMlfqLevel(2);
                }
            }
        }

        // Sort: Primary by MLFQ Level ascending (0 is highest priority); Secondary by FCFS arrival
        sorted.sort(Comparator
                .comparingInt(Job::getMlfqLevel)
                .thenComparing(Job::getCreatedAt));

        return sorted;
    }

    public void triggerManualBoost() {
        this.lastBoostTime = Instant.now().minusSeconds(boostIntervalSeconds + 1);
    }

    public long getLevel0ThresholdMs() {
        return level0ThresholdMs;
    }

    public long getLevel1ThresholdMs() {
        return level1ThresholdMs;
    }

    public long getBoostIntervalSeconds() {
        return boostIntervalSeconds;
    }
}
