package com.queuepilot.scheduler;

import com.queuepilot.model.Job;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Priority Scheduling with Aging.
 * Prevents starvation of low-priority tasks by boosting effective priority
 * as time elapses in the queue.
 */
@Component("PRIORITY_AGING")
public class PriorityAgingSchedulingPolicy implements SchedulingPolicy {

    private final long agingIntervalSeconds;
    private final int agingIncrement;

    public PriorityAgingSchedulingPolicy(
            @Value("${queuepilot.scheduler.aging-interval-seconds:3}") long agingIntervalSeconds,
            @Value("${queuepilot.scheduler.aging-increment:1}") int agingIncrement) {
        this.agingIntervalSeconds = Math.max(1, agingIntervalSeconds);
        this.agingIncrement = Math.max(1, agingIncrement);
    }

    public PriorityAgingSchedulingPolicy() {
        this(3, 1);
    }

    @Override
    public String getPolicyName() {
        return "PRIORITY_AGING";
    }

    @Override
    public List<Job> orderJobs(List<Job> candidateJobs, Instant now) {
        if (candidateJobs == null || candidateJobs.isEmpty()) {
            return List.of();
        }
        List<Job> sorted = new ArrayList<>(candidateJobs);

        // Dynamically age all waiting jobs
        for (Job job : sorted) {
            job.updateEffectivePriority(now, agingIntervalSeconds, agingIncrement);
        }

        // Sort: Highest effective priority first; tie-break by earlier creation time
        sorted.sort(Comparator
                .comparingInt(Job::getEffectivePriority).reversed()
                .thenComparing(Job::getCreatedAt));

        return sorted;
    }

    public long getAgingIntervalSeconds() {
        return agingIntervalSeconds;
    }

    public int getAgingIncrement() {
        return agingIncrement;
    }
}
