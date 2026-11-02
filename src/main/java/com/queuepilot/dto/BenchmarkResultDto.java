package com.queuepilot.dto;

public record BenchmarkResultDto(
        String policyName,
        int jobCount,
        double avgWaitingTimeMs,
        double avgTurnaroundTimeMs,
        long totalDurationMs,
        double throughputJobsPerSec,
        double workerUtilizationPercent
) {}
