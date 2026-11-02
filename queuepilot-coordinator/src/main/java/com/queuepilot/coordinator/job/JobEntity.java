package com.queuepilot.coordinator.job;

import com.queuepilot.coordinator.task.ExecutorKind;
import com.queuepilot.coordinator.task.TaskDefinitionEntity;
import com.queuepilot.core.job.JobState;
import com.queuepilot.core.job.JobStateMachine;
import com.queuepilot.core.job.RetryPolicy;
import com.queuepilot.core.scheduling.SchedulableJob;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.DynamicUpdate;

import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * A job and its lifecycle. State changes only through the intention-revealing methods below,
 * each of which checks {@link JobStateMachine} — callers cannot put a job into an illegal state.
 *
 * <p>{@code @Version} gives optimistic locking: if two workers try to claim the same job, only the
 * first commit succeeds. {@code @DynamicUpdate} writes only changed columns, so frequent progress
 * updates do not overwrite unrelated fields.</p>
 */
@Entity
@Table(name = "job")
@DynamicUpdate
public class JobEntity {

    @Id
    private String id;
    private String name;
    private String taskDefinitionId;
    private String taskType;
    private String taskVersion;
    @Enumerated(EnumType.STRING)
    private ExecutorKind executorKind;
    private String requiredCapabilities;
    private String workflowId;
    private String workflowStep;
    private String queueName;
    private String clientId;
    @Enumerated(EnumType.STRING)
    private JobState state;
    private int priority;
    private long estimatedMs;
    private String inputJson;
    private String outputJson;
    private String outputArtifactIds;
    private String errorCode;
    private String errorMessage;
    private int progressPercent;
    private String progressMessage;
    private String currentAttemptId;
    private String currentWorkerId;
    private int attemptsUsed;
    private int maxRetries;
    private int timeoutSeconds;
    private Instant submittedAt;
    private Instant startAt;
    private Instant readyAt;
    private Instant deadlineAt;
    private Instant startedAt;
    private Instant finishedAt;
    @Version
    private long version;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "job_dependency", joinColumns = @JoinColumn(name = "job_id"))
    @Column(name = "prerequisite_id")
    private Set<String> prerequisiteIds = new HashSet<>();

    protected JobEntity() {
    }

    public JobEntity(String id, NewJob spec, TaskDefinitionEntity task, Instant now) {
        this.id = id;
        this.name = spec.name();
        this.taskDefinitionId = task.getId();
        this.taskType = task.getTaskType();
        this.taskVersion = task.getVersion();
        this.executorKind = task.getExecutorKind();
        this.requiredCapabilities = task.getRequiredCapabilities();
        this.workflowId = spec.workflowId();
        this.workflowStep = spec.workflowStep();
        this.queueName = spec.queue();
        this.clientId = spec.clientId();
        this.priority = spec.priority();
        this.estimatedMs = spec.estimatedMs();
        this.inputJson = spec.inputJson();
        this.maxRetries = task.getMaxRetries();
        this.timeoutSeconds = task.getTimeoutSeconds();
        this.submittedAt = now;
        this.startAt = spec.startAt();
        this.deadlineAt = spec.deadline();
        this.prerequisiteIds = new HashSet<>(spec.prerequisiteIds());
        this.state = spec.initialState();
        this.readyAt = spec.initialState() == JobState.QUEUED ? now : null;
        this.progressMessage = switch (spec.initialState()) {
            case SCHEDULED -> "Scheduled for " + spec.startAt();
            case BLOCKED -> "Waiting for prerequisites";
            case QUEUED -> "Waiting for a worker";
            case SKIPPED -> "Skipped: a prerequisite did not succeed";
            default -> throw new IllegalArgumentException("Invalid initial state " + spec.initialState());
        };
        if (spec.initialState() == JobState.SKIPPED) {
            this.errorCode = "UPSTREAM_FAILED";
            this.errorMessage = "A prerequisite had already failed or was cancelled";
            this.finishedAt = now;
        }
    }

    /** Everything needed to create a job, already validated by {@link JobService}. */
    public record NewJob(String name, String queue, String clientId, int priority, long estimatedMs, String inputJson,
                         Instant startAt, Instant deadline, Set<String> prerequisiteIds, String workflowId,
                         String workflowStep, JobState initialState) {
    }

    private void moveTo(JobState next) {
        JobStateMachine.check(state, next);
        state = next;
    }

    /** SCHEDULED/BLOCKED -> QUEUED once start time and prerequisites allow it. */
    public void release(Instant now) {
        moveTo(JobState.QUEUED);
        readyAt = now;
        progressMessage = "Waiting for a worker";
    }

    /** SCHEDULED -> BLOCKED when the start time arrives but prerequisites are not done. */
    public void block() {
        moveTo(JobState.BLOCKED);
        progressMessage = "Waiting for prerequisites";
    }

    public void start(String attemptId, String workerId, Instant now) {
        moveTo(JobState.RUNNING);
        currentAttemptId = attemptId;
        currentWorkerId = workerId;
        attemptsUsed++;
        if (startedAt == null) {
            startedAt = now;
        }
        progressPercent = 0;
        progressMessage = "Attempt " + attemptsUsed + " started on " + workerId;
    }

    public void succeed(String outputJson, String outputArtifactIds, Instant now) {
        moveTo(JobState.SUCCEEDED);
        this.outputJson = outputJson;
        this.outputArtifactIds = outputArtifactIds;
        this.errorCode = null;
        this.errorMessage = null;
        this.finishedAt = now;
        this.progressPercent = 100;
        this.progressMessage = "Completed";
    }

    /** RUNNING -> QUEUED with a retry delay. */
    public void retryLater(Instant readyAt, String code, String message) {
        moveTo(JobState.QUEUED);
        this.readyAt = readyAt;
        this.errorCode = code;
        this.errorMessage = message;
        this.currentAttemptId = null;
        this.currentWorkerId = null;
        this.progressPercent = 0;
        this.progressMessage = "Retry " + attemptsUsed + "/" + maxRetries + " after: " + code;
    }

    public void fail(String code, String message, Instant now) {
        moveTo(JobState.FAILED);
        this.errorCode = code;
        this.errorMessage = message;
        this.currentWorkerId = null;
        this.finishedAt = now;
        this.progressMessage = "Failed: " + code;
    }

    public void cancel(Instant now) {
        moveTo(JobState.CANCELLED);
        this.finishedAt = now;
        this.progressMessage = "Cancelled";
    }

    public void skip(String reason, Instant now) {
        moveTo(JobState.SKIPPED);
        this.errorCode = "UPSTREAM_FAILED";
        this.errorMessage = reason;
        this.finishedAt = now;
        this.progressMessage = "Skipped";
    }

    public boolean isCurrentAttempt(String attemptId) {
        return state == JobState.RUNNING && attemptId != null && attemptId.equals(currentAttemptId);
    }

    public boolean canRetry() {
        return RetryPolicy.canRetry(attemptsUsed, maxRetries);
    }

    public String taskIdentifier() {
        return taskType + "@" + taskVersion;
    }

    public Set<String> requiredCapabilitySet() {
        return TaskDefinitionEntity.splitCsv(requiredCapabilities);
    }

    public SchedulableJob toSchedulable() {
        return new SchedulableJob(id, queueName, priority, estimatedMs, submittedAt);
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getTaskDefinitionId() { return taskDefinitionId; }
    public String getTaskType() { return taskType; }
    public String getTaskVersion() { return taskVersion; }
    public ExecutorKind getExecutorKind() { return executorKind; }
    public String getWorkflowId() { return workflowId; }
    public String getWorkflowStep() { return workflowStep; }
    public String getQueueName() { return queueName; }
    public String getClientId() { return clientId; }
    public JobState getState() { return state; }
    public int getPriority() { return priority; }
    public long getEstimatedMs() { return estimatedMs; }
    public String getInputJson() { return inputJson; }
    public String getOutputJson() { return outputJson; }
    public String getOutputArtifactIds() { return outputArtifactIds; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public int getProgressPercent() { return progressPercent; }
    public String getProgressMessage() { return progressMessage; }
    public String getCurrentAttemptId() { return currentAttemptId; }
    public String getCurrentWorkerId() { return currentWorkerId; }
    public int getAttemptsUsed() { return attemptsUsed; }
    public int getMaxRetries() { return maxRetries; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public Instant getSubmittedAt() { return submittedAt; }
    public Instant getStartAt() { return startAt; }
    public Instant getReadyAt() { return readyAt; }
    public Instant getDeadlineAt() { return deadlineAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public Set<String> getPrerequisiteIds() { return Collections.unmodifiableSet(prerequisiteIds); }
}
