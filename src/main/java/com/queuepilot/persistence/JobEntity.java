package com.queuepilot.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "jobs")
public class JobEntity {

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "job_type", nullable = false)
    private String jobType;

    @Column(name = "state", nullable = false)
    private String state;

    @Column(name = "base_priority")
    private int basePriority;

    @Column(name = "effective_priority")
    private int effectivePriority;

    @Column(name = "estimated_duration_ms")
    private long estimatedDurationMs;

    @Column(name = "retry_count")
    private int retryCount;

    @Column(name = "max_retries")
    private int maxRetries;

    @Column(name = "progress_percent")
    private int progressPercent;

    @Column(name = "progress_message")
    private String progressMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "scheduled_start_time")
    private Instant scheduledStartTime;

    @Column(name = "deadline")
    private Instant deadline;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "assigned_worker_id")
    private String assignedWorkerId;

    @Column(name = "attempt_token")
    private String attemptToken;

    @Lob
    @Column(name = "result_summary")
    private String resultSummary;

    @Lob
    @Column(name = "error_message")
    private String errorMessage;

    public JobEntity() {}

    // Getters and setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getJobType() { return jobType; }
    public void setJobType(String jobType) { this.jobType = jobType; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public int getBasePriority() { return basePriority; }
    public void setBasePriority(int basePriority) { this.basePriority = basePriority; }
    public int getEffectivePriority() { return effectivePriority; }
    public void setEffectivePriority(int effectivePriority) { this.effectivePriority = effectivePriority; }
    public long getEstimatedDurationMs() { return estimatedDurationMs; }
    public void setEstimatedDurationMs(long estimatedDurationMs) { this.estimatedDurationMs = estimatedDurationMs; }
    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    public int getProgressPercent() { return progressPercent; }
    public void setProgressPercent(int progressPercent) { this.progressPercent = progressPercent; }
    public String getProgressMessage() { return progressMessage; }
    public void setProgressMessage(String progressMessage) { this.progressMessage = progressMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getScheduledStartTime() { return scheduledStartTime; }
    public void setScheduledStartTime(Instant scheduledStartTime) { this.scheduledStartTime = scheduledStartTime; }
    public Instant getDeadline() { return deadline; }
    public void setDeadline(Instant deadline) { this.deadline = deadline; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public String getAssignedWorkerId() { return assignedWorkerId; }
    public void setAssignedWorkerId(String assignedWorkerId) { this.assignedWorkerId = assignedWorkerId; }
    public String getAttemptToken() { return attemptToken; }
    public void setAttemptToken(String attemptToken) { this.attemptToken = attemptToken; }
    public String getResultSummary() { return resultSummary; }
    public void setResultSummary(String resultSummary) { this.resultSummary = resultSummary; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
