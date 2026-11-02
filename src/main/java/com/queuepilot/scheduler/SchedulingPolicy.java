package com.queuepilot.scheduler;

import com.queuepilot.model.Job;
import com.queuepilot.model.Worker;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Strategy Pattern interface for scheduling algorithms.
 * Allows runtime switching between FCFS, SJF, and Priority with Aging.
 */
public interface SchedulingPolicy {

    /**
     * Name/identifier of the scheduling policy.
     */
    String getPolicyName();

    /**
     * Orders candidate runnable jobs according to policy rules.
     *
     * @param candidateJobs List of eligible runnable jobs
     * @param now Current timestamp for time-dependent calculations
     * @return Sorted list of jobs in dispatch priority order
     */
    List<Job> orderJobs(List<Job> candidateJobs, Instant now);

    /**
     * Selects the highest priority job that the specific worker is capable of executing.
     *
     * @param candidateJobs List of candidate runnable jobs
     * @param worker The idle worker awaiting assignment
     * @param now Current timestamp
     * @return The best matching job, or empty if none matches worker capabilities
     */
    default Optional<Job> selectNextJob(List<Job> candidateJobs, Worker worker, Instant now) {
        if (candidateJobs == null || candidateJobs.isEmpty() || worker == null) {
            return Optional.empty();
        }
        List<Job> ordered = orderJobs(candidateJobs, now);
        return ordered.stream()
                .filter(worker::canExecute)
                .findFirst();
    }
}
