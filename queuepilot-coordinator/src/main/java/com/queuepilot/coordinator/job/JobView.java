package com.queuepilot.coordinator.job;

import com.queuepilot.core.job.JobState;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/** JSON representation of a job. {@code input}/{@code output} are only filled for single-job requests. */
public record JobView(String id,
                      String label,
                      String taskType,
                      String queue,
                      JobState state,
                      int priority,
                      long estimatedMs,
                      int progressPercent,
                      String progressMessage,
                      int attemptsUsed,
                      String currentWorkerId,
                      String currentAttemptId,
                      Set<String> dependsOn,
                      String workflowId,
                      String workflowStep,
                      Instant submittedAt,
                      Instant readyAt,
                      Instant startedAt,
                      Instant finishedAt,
                      String error,
                      Map<String, Object> input,
                      Map<String, Object> output,
                      String outputArtifactIds) {

    public static JobView summary(JobEntity j) {
        return of(j, null, null);
    }

    public static JobView of(JobEntity j, Map<String, Object> input, Map<String, Object> output) {
        return new JobView(j.getId(), j.label(), j.getTaskType(), j.getQueueName(), j.getState(), j.getPriority(),
                j.getEstimatedMs(), j.getProgressPercent(), j.getProgressMessage(), j.getAttemptsUsed(),
                j.getCurrentWorkerId(), j.getCurrentAttemptId(), j.getPrerequisiteIds(), j.getWorkflowId(),
                j.getWorkflowStep(), j.getSubmittedAt(), j.getReadyAt(), j.getStartedAt(), j.getFinishedAt(),
                j.getError(), input, output, j.getOutputArtifactIds());
    }
}
