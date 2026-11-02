package com.queuepilot.model;

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Base abstract class representing a unit of computational work in QueuePilot.
 * Demonstrates encapsulation, polymorphic behavior, and state lifecycle management.
 */
public abstract class Job implements Serializable {

    private final String id;
    private String name;
    private final JobType type;
    private JobState state;

    private int basePriority;
    private int effectivePriority;
    private long estimatedDurationMs;
    private int mlfqLevel = 0;

    private final Set<String> dependencies = new HashSet<>();
    private final Set<String> requiredCapabilities = new HashSet<>();

    private int retryCount = 0;
    private int maxRetries = 3;

    private int progressPercent = 0;
    private String progressMessage = "Created";

    private Instant createdAt;
    private Instant scheduledStartTime;
    private Instant deadline;
    private Instant startedAt;
    private Instant completedAt;

    private String assignedWorkerId;
    private String attemptToken;
    private Instant leaseExpiresAt;

    private JobResult result;
    private String failureReason;

    protected Job(String name, JobType type, int basePriority, long estimatedDurationMs) {
        this(name, type, basePriority, estimatedDurationMs, Instant.now());
    }

    protected Job(String name, JobType type, int basePriority, long estimatedDurationMs, Instant createdAt) {
        this.id = UUID.randomUUID().toString();
        this.name = (name != null && !name.isBlank()) ? name : type.name() + "-" + id.substring(0, 8);
        this.type = Objects.requireNonNull(type, "JobType cannot be null");
        this.basePriority = Math.max(1, basePriority);
        this.effectivePriority = this.basePriority;
        this.estimatedDurationMs = Math.max(100L, estimatedDurationMs);
        this.state = JobState.QUEUED;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.requiredCapabilities.add(type.getDefaultCapabilityTag());
    }

    /**
     * Polymorphic hook to get task-specific summary.
     */
    public abstract String getSummary();

    /**
     * Polymorphic hook to inspect specific task payload.
     */
    public abstract Object getPayload();

    /**
     * Recalculates effective priority based on waiting duration (Priority Aging).
     * effectivePriority = basePriority + (waitingSeconds / agingInterval) * agingIncrement
     */
    public synchronized void updateEffectivePriority(Instant now, long agingIntervalSeconds, int agingIncrement) {
        if (state != JobState.QUEUED) {
            return;
        }
        Instant referenceTime = scheduledStartTime != null && scheduledStartTime.isAfter(createdAt) 
                ? scheduledStartTime 
                : createdAt;
        
        long waitingSeconds = Duration.between(referenceTime, now).getSeconds();
        if (waitingSeconds > 0 && agingIntervalSeconds > 0) {
            long agingBoost = (waitingSeconds / agingIntervalSeconds) * agingIncrement;
            this.effectivePriority = (int) Math.min(Integer.MAX_VALUE, (long) basePriority + agingBoost);
        } else {
            this.effectivePriority = basePriority;
        }
    }

    public synchronized void updateProgress(int percent, String message) {
        this.progressPercent = Math.min(100, Math.max(0, percent));
        if (message != null && !message.isBlank()) {
            this.progressMessage = message;
        }
    }

    public synchronized void markScheduled(Instant scheduledTime) {
        this.scheduledStartTime = scheduledTime;
        this.state = JobState.SCHEDULED;
    }

    public synchronized void markBlocked() {
        this.state = JobState.BLOCKED;
    }

    public synchronized void markQueued() {
        this.state = JobState.QUEUED;
        this.progressMessage = "Queued for scheduling";
    }

    public synchronized void markRunning(String workerId, String attemptToken, Instant leaseExpiry) {
        this.state = JobState.RUNNING;
        this.assignedWorkerId = workerId;
        this.attemptToken = attemptToken;
        this.leaseExpiresAt = leaseExpiry;
        if (this.startedAt == null) {
            this.startedAt = Instant.now();
        }
        this.progressMessage = "Running on worker " + workerId;
    }

    public synchronized void markCompleted(JobResult result) {
        this.state = JobState.COMPLETED;
        this.result = result;
        this.completedAt = Instant.now();
        this.progressPercent = 100;
        this.progressMessage = "Completed successfully";
    }

    public synchronized void markFailed(String reason) {
        this.state = JobState.FAILED;
        this.failureReason = reason;
        this.completedAt = Instant.now();
        this.progressMessage = "Failed: " + reason;
    }

    public synchronized void markCancelled(String reason) {
        this.state = JobState.CANCELLED;
        this.failureReason = reason;
        this.completedAt = Instant.now();
        this.progressMessage = "Cancelled: " + reason;
    }

    public synchronized void renewLease(Instant newLeaseExpiry) {
        this.leaseExpiresAt = newLeaseExpiry;
    }

    public synchronized void incrementRetryCount() {
        this.retryCount++;
    }

    public synchronized boolean canRetry() {
        return this.retryCount < this.maxRetries;
    }

    // --- Dependencies ---
    public void addDependency(String prerequisiteJobId) {
        if (prerequisiteJobId != null && !prerequisiteJobId.isBlank()) {
            this.dependencies.add(prerequisiteJobId);
        }
    }

    public Set<String> getDependencies() {
        return Collections.unmodifiableSet(dependencies);
    }

    // --- Capabilities ---
    public void addRequiredCapability(String capabilityTag) {
        if (capabilityTag != null && !capabilityTag.isBlank()) {
            this.requiredCapabilities.add(capabilityTag);
        }
    }

    public Set<String> getRequiredCapabilities() {
        return Collections.unmodifiableSet(requiredCapabilities);
    }

    // --- Getters & Setters ---
    public String getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public JobType getType() { return type; }
    public synchronized JobState getState() { return state; }
    public synchronized void setState(JobState state) { this.state = state; }
    public int getBasePriority() { return basePriority; }
    public void setBasePriority(int basePriority) { this.basePriority = basePriority; }
    public synchronized int getEffectivePriority() { return effectivePriority; }
    public synchronized int getMlfqLevel() { return mlfqLevel; }
    public synchronized void setMlfqLevel(int mlfqLevel) { this.mlfqLevel = Math.max(0, mlfqLevel); }
    public long getEstimatedDurationMs() { return estimatedDurationMs; }
    public void setEstimatedDurationMs(long estimatedDurationMs) { this.estimatedDurationMs = estimatedDurationMs; }
    public int getRetryCount() { return retryCount; }
    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    public synchronized int getProgressPercent() { return progressPercent; }
    public synchronized String getProgressMessage() { return progressMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getScheduledStartTime() { return scheduledStartTime; }
    public void setScheduledStartTime(Instant scheduledStartTime) { this.scheduledStartTime = scheduledStartTime; }
    public Instant getDeadline() { return deadline; }
    public void setDeadline(Instant deadline) { this.deadline = deadline; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public synchronized String getAssignedWorkerId() { return assignedWorkerId; }
    public synchronized void setAssignedWorkerId(String assignedWorkerId) { this.assignedWorkerId = assignedWorkerId; }
    public synchronized String getAttemptToken() { return attemptToken; }
    public synchronized Instant getLeaseExpiresAt() { return leaseExpiresAt; }
    public synchronized JobResult getResult() { return result; }
    public synchronized String getFailureReason() { return failureReason; }

    /**
     * Computes Turnaround Time (Completion Time - Creation Time).
     */
    public Long getTurnaroundTimeMs() {
        if (completedAt == null) return null;
        return Duration.between(createdAt, completedAt).toMillis();
    }

    /**
     * Computes Total Waiting Time (Start Time - Creation Time).
     */
    public Long getWaitingTimeMs() {
        if (startedAt == null) return null;
        return Duration.between(createdAt, startedAt).toMillis();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Job job = (Job) o;
        return Objects.equals(id, job.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", state=" + state +
                ", priority=" + effectivePriority +
                ", estimatedMs=" + estimatedDurationMs +
                '}';
    }
}
