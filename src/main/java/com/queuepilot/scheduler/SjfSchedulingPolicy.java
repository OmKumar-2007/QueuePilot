package com.queuepilot.scheduler;

import com.queuepilot.model.Job;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Shortest Job First (SJF) Scheduling Policy.
 * Dispatches jobs with shortest estimated execution duration first.
 * Ties are broken using creation time (FCFS).
 */
@Component("SJF")
public class SjfSchedulingPolicy implements SchedulingPolicy {

    @Override
    public String getPolicyName() {
        return "SJF";
    }

    @Override
    public List<Job> orderJobs(List<Job> candidateJobs, Instant now) {
        if (candidateJobs == null || candidateJobs.isEmpty()) {
            return List.of();
        }
        List<Job> sorted = new ArrayList<>(candidateJobs);
        sorted.sort(Comparator
                .comparingLong(Job::getEstimatedDurationMs)
                .thenComparing(Job::getCreatedAt));
        return sorted;
    }
}
