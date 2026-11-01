package com.queuepilot.dto;

import com.queuepilot.model.Job;
import com.queuepilot.model.JobResult;

import java.time.Instant;
import java.util.Set;

public record JobResponse(
        String id,
        String name,
        String type,
        String state,
        int basePriority,
        int effectivePriority,
        int mlfqLevel,
        long estimatedDurationMs,
        Set<String> dependencies,
        Set<String> requiredCapabilities,
        int retryCount,
        int maxRetries,
        int progressPercent,
        String progressMessage,
        Instant createdAt,
        Instant scheduledStartTime,
        Instant deadline,
        Instant startedAt,
        Instant completedAt,
        String assignedWorkerId,
        String attemptToken,
        String summary,
        JobResult result,
        String failureReason,
        Long turnaroundTimeMs,
        Long waitingTimeMs
) {
    public static JobResponse from(Job job) {
        if (job == null) return null;
        return new JobResponse(
                job.getId(),
                job.getName(),
                job.getType().name(),
                job.getState().name(),
                job.getBasePriority(),
                job.getEffectivePriority(),
                job.getMlfqLevel(),
                job.getEstimatedDurationMs(),
                job.getDependencies(),
                job.getRequiredCapabilities(),
                job.getRetryCount(),
                job.getMaxRetries(),
                job.getProgressPercent(),
                job.getProgressMessage(),
                job.getCreatedAt(),
                job.getScheduledStartTime(),
                job.getDeadline(),
                job.getStartedAt(),
                job.getCompletedAt(),
                job.getAssignedWorkerId(),
                job.getAttemptToken(),
                job.getSummary(),
                job.getResult(),
                job.getFailureReason(),
                job.getTurnaroundTimeMs(),
                job.getWaitingTimeMs()
        );
    }
}
