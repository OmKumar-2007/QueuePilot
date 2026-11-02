package com.queuepilot.scheduler;

import com.queuepilot.model.Job;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * First-Come, First-Served (FCFS) Scheduling Policy.
 * Dispatches jobs in strict chronological arrival order.
 */
@Component("FCFS")
public class FcFSSchedulingPolicy implements SchedulingPolicy {

    @Override
    public String getPolicyName() {
        return "FCFS";
    }

    @Override
    public List<Job> orderJobs(List<Job> candidateJobs, Instant now) {
        if (candidateJobs == null || candidateJobs.isEmpty()) {
            return List.of();
        }
        List<Job> sorted = new ArrayList<>(candidateJobs);
        sorted.sort(Comparator.comparing(Job::getCreatedAt));
        return sorted;
    }
}
