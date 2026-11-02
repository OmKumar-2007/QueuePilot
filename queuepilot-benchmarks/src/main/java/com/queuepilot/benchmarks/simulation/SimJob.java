package com.queuepilot.benchmarks.simulation;

/**
 * A synthetic job for the scheduling simulation. {@code durationMs} is a declared value: the
 * simulation assumes the job runs exactly that long, which is what makes it a simulation.
 */
public record SimJob(String id, int priority, long durationMs, long arrivalMs) {
}
