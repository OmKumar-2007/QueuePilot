package com.queuepilot.core.metrics;

/**
 * Standard scheduling metrics for a set of finished jobs.
 *
 * @param maxWaitMs   the longest any job waited: the starvation indicator
 * @param makespanMs  first submission to last completion
 * @param utilization busy worker time / (slots * makespan), 0..1
 */
public record ScheduleMetrics(int jobCount,
                              double avgWaitMs,
                              double avgTurnaroundMs,
                              double p95WaitMs,
                              double maxWaitMs,
                              double makespanMs,
                              double throughputPerSec,
                              double utilization) {
}
