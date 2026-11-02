package com.queuepilot.coordinator.job;

import com.queuepilot.core.job.JobState;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

/** JSON representation of a job. {@code input}/{@code output} are only filled for single-job requests. */
public record JobView(String id,
                      String name,
                      String taskType,
                      String taskVersion,
                      String queue,
                      String clientId,
                      JobState state,
                      int priority,
                      long estimatedMs,
                      int progressPercent,
                      String progressMessage,
                      int attemptsUsed,
                      int maxRetries,
                      String currentWorkerId,
                      String currentAttemptId,
                      Set<String> dependsOn,
                      String workflowId,
                      String workflowStep,
                      Instant submittedAt,
                      Instant startAt,
                      Instant readyAt,
                      Instant deadline,
                      DeadlineStatus deadlineStatus,
                      Instant startedAt,
                      Instant finishedAt,
                      String errorCode,
                      String errorMessage,
                      Map<String, Object> input,
                      Map<String, Object> output,
                      String outputArtifactIds) {

    /** How a job relates to its deadline (F13). AT_RISK = less than 5 minutes left. */
    public enum DeadlineStatus { NONE, ON_TRACK, AT_RISK, OVERDUE, MET, MISSED }

    static final Duration AT_RISK_WINDOW = Duration.ofMinutes(5);

    public static DeadlineStatus deadlineStatus(JobEntity job, Instant now) {
        Instant deadline = job.getDeadlineAt();
        if (deadline == null) {
            return DeadlineStatus.NONE;
        }
        if (job.getState().isTerminal()) {
            boolean met = job.getState() == JobState.SUCCEEDED && !job.getFinishedAt().isAfter(deadline);
            return met ? DeadlineStatus.MET : DeadlineStatus.MISSED;
        }
        if (now.isAfter(deadline)) {
            return DeadlineStatus.OVERDUE;
        }
        return now.plus(AT_RISK_WINDOW).isAfter(deadline) ? DeadlineStatus.AT_RISK : DeadlineStatus.ON_TRACK;
    }

    public static JobView summary(JobEntity j, Instant now) {
        return of(j, now, null, null);
    }

    public static JobView of(JobEntity j, Instant now, Map<String, Object> input, Map<String, Object> output) {
        return new JobView(j.getId(), j.getName(), j.getTaskType(), j.getTaskVersion(), j.getQueueName(), j.getClientId(),
                j.getState(), j.getPriority(), j.getEstimatedMs(), j.getProgressPercent(), j.getProgressMessage(),
                j.getAttemptsUsed(), j.getMaxRetries(), j.getCurrentWorkerId(), j.getCurrentAttemptId(),
                j.getPrerequisiteIds(), j.getWorkflowId(), j.getWorkflowStep(), j.getSubmittedAt(), j.getStartAt(),
                j.getReadyAt(), j.getDeadlineAt(), deadlineStatus(j, now), j.getStartedAt(), j.getFinishedAt(),
                j.getErrorCode(), j.getErrorMessage(), input, output, j.getOutputArtifactIds());
    }
}
