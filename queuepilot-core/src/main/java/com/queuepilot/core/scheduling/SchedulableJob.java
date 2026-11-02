package com.queuepilot.core.scheduling;

import java.time.Instant;

/**
 * Everything a scheduling policy is allowed to know about a job. Deliberately contains nothing
 * task-specific: the scheduler never knows what a job does.
 *
 * @param priority    1 (lowest) .. 10 (highest)
 * @param estimatedMs declared execution estimate, used by SJF
 * @param submittedAt arrival time, used by FCFS and for aging
 */
public record SchedulableJob(String id, String queue, int priority, long estimatedMs, Instant submittedAt) {
}
