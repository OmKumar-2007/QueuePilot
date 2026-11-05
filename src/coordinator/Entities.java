package coordinator;

import core.Jobs.AttemptState;
import core.Jobs.IllegalStateTransitionException;
import core.Jobs.JobState;
import core.Jobs.JobStateMachine;
import core.Jobs.RetryPolicy;
import core.Jobs.WorkerStatus;
import core.Scheduling.PolicyType;
import core.Scheduling.SchedulableJob;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.DynamicUpdate;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
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
class JobEntity {

    @Id
    private String id;
    private String taskType;
    private String workflowId;
    private String workflowStep;
    private String queueName;
    @Enumerated(EnumType.STRING)
    private JobState state;
    private int priority;
    private long estimatedMs;
    private String inputJson;
    private String outputJson;
    private String outputArtifactIds;
    private String error;
    private int progressPercent;
    private String progressMessage;
    private String currentAttemptId;
    private String currentWorkerId;
    private int attemptsUsed;
    private Instant submittedAt;
    private Instant readyAt;
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

    public JobEntity(String id, NewJob spec, Instant now) {
        this.id = id;
        this.taskType = spec.taskType();
        this.workflowId = spec.workflowId();
        this.workflowStep = spec.workflowStep();
        this.queueName = spec.queue();
        this.priority = spec.priority();
        this.estimatedMs = spec.estimatedMs();
        this.inputJson = spec.inputJson();
        this.submittedAt = now;
        this.prerequisiteIds = new HashSet<>(spec.prerequisiteIds());
        this.state = spec.initialState();
        this.readyAt = spec.initialState() == JobState.QUEUED ? now : null;
        this.progressMessage = switch (spec.initialState()) {
            case BLOCKED -> "Waiting for prerequisites";
            case QUEUED -> "Waiting for a worker";
            case SKIPPED -> "Skipped: a prerequisite did not succeed";
            default -> throw new IllegalArgumentException("Invalid initial state " + spec.initialState());
        };
        if (spec.initialState() == JobState.SKIPPED) {
            this.error = "UPSTREAM_FAILED: a prerequisite had already failed or was cancelled";
            this.finishedAt = now;
        }
    }

    /** Everything needed to create a job, already validated by {@link JobService}. */
    public record NewJob(String taskType, String queue, int priority, long estimatedMs, String inputJson,
                         Set<String> prerequisiteIds, String workflowId, String workflowStep, JobState initialState) {
    }

    private void moveTo(JobState next) {
        JobStateMachine.check(state, next);
        state = next;
    }

    /** BLOCKED -> QUEUED once all prerequisites have succeeded. */
    public void release(Instant now) {
        moveTo(JobState.QUEUED);
        readyAt = now;
        progressMessage = "Waiting for a worker";
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
        this.error = null;
        this.finishedAt = now;
        this.progressPercent = 100;
        this.progressMessage = "Completed";
    }

    /** RUNNING -> QUEUED with a retry delay. */
    public void retryLater(Instant readyAt, String error, int maxRetries) {
        moveTo(JobState.QUEUED);
        this.readyAt = readyAt;
        this.error = error;
        this.currentAttemptId = null;
        this.currentWorkerId = null;
        this.progressPercent = 0;
        this.progressMessage = "Retry " + attemptsUsed + "/" + maxRetries + " after: " + error;
    }

    public void fail(String error, Instant now) {
        moveTo(JobState.FAILED);
        this.error = error;
        this.currentWorkerId = null;
        this.finishedAt = now;
        this.progressMessage = "Failed";
    }

    public void cancel(Instant now) {
        moveTo(JobState.CANCELLED);
        this.finishedAt = now;
        this.progressMessage = "Cancelled";
    }

    public void skip(String reason, Instant now) {
        moveTo(JobState.SKIPPED);
        this.error = "UPSTREAM_FAILED: " + reason;
        this.finishedAt = now;
        this.progressMessage = "Skipped";
    }

    public boolean isCurrentAttempt(String attemptId) {
        return state == JobState.RUNNING && attemptId != null && attemptId.equals(currentAttemptId);
    }

    public boolean canRetry(int maxRetries) {
        return RetryPolicy.canRetry(attemptsUsed, maxRetries);
    }

    /** Short readable name for logs and events, e.g. {@code sorting #1a2b3c4d}. */
    public String label() {
        return taskType + " #" + id.substring(0, Math.min(8, id.length()));
    }

    public SchedulableJob toSchedulable() {
        return new SchedulableJob(id, queueName, priority, estimatedMs, submittedAt);
    }

    public String getId() { return id; }
    public String getTaskType() { return taskType; }
    public String getWorkflowId() { return workflowId; }
    public String getWorkflowStep() { return workflowStep; }
    public String getQueueName() { return queueName; }
    public JobState getState() { return state; }
    public int getPriority() { return priority; }
    public long getEstimatedMs() { return estimatedMs; }
    public String getInputJson() { return inputJson; }
    public String getOutputJson() { return outputJson; }
    public String getOutputArtifactIds() { return outputArtifactIds; }
    public String getError() { return error; }
    public int getProgressPercent() { return progressPercent; }
    public String getProgressMessage() { return progressMessage; }
    public String getCurrentAttemptId() { return currentAttemptId; }
    public String getCurrentWorkerId() { return currentWorkerId; }
    public int getAttemptsUsed() { return attemptsUsed; }
    public Instant getSubmittedAt() { return submittedAt; }
    public Instant getReadyAt() { return readyAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public Set<String> getPrerequisiteIds() { return Collections.unmodifiableSet(prerequisiteIds); }
}

/**
 * One execution attempt of a job on a worker. Only the worker that owns a running attempt may
 * update it; a late update from an older attempt is rejected because the job's current attempt
 * id no longer matches (see {@code JobEntity.isCurrentAttempt}).
 */
@Entity
@Table(name = "attempt")
class AttemptEntity {

    @Id
    private String id;
    private String jobId;
    private String workerId;
    private int attemptNumber;
    @Enumerated(EnumType.STRING)
    private AttemptState state;
    private Instant startedAt;
    private Instant leaseExpiresAt;
    private Instant finishedAt;
    private String error;

    protected AttemptEntity() {
    }

    public AttemptEntity(String id, String jobId, String workerId, int attemptNumber, Instant now, Duration lease) {
        this.id = id;
        this.jobId = jobId;
        this.workerId = workerId;
        this.attemptNumber = attemptNumber;
        this.state = AttemptState.RUNNING;
        this.startedAt = now;
        this.leaseExpiresAt = now.plus(lease);
    }

    /** True only for the worker that owns this attempt, while it is running. */
    public boolean accepts(String workerId) {
        return state == AttemptState.RUNNING && this.workerId.equals(workerId);
    }

    public void renewLease(Instant now, Duration lease) {
        leaseExpiresAt = now.plus(lease);
    }

    public void finish(AttemptState outcome, String error, Instant now) {
        if (state != AttemptState.RUNNING || outcome == AttemptState.RUNNING) {
            throw new IllegalStateTransitionException("Attempt " + id + " is already " + state);
        }
        state = outcome;
        this.error = error != null && error.length() > 2000 ? error.substring(0, 2000) : error;
        finishedAt = now;
    }

    public boolean leaseExpired(Instant now) {
        return state == AttemptState.RUNNING && leaseExpiresAt.isBefore(now);
    }

    public boolean exceededTimeout(Instant now, int timeoutSeconds) {
        return state == AttemptState.RUNNING && startedAt.plusSeconds(timeoutSeconds).isBefore(now);
    }

    public String getId() { return id; }
    public String getJobId() { return jobId; }
    public String getWorkerId() { return workerId; }
    public int getAttemptNumber() { return attemptNumber; }
    public AttemptState getState() { return state; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getLeaseExpiresAt() { return leaseExpiresAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public String getError() { return error; }
}

/** A registered worker process. Running-attempt counts are derived from the attempt table, not stored here. */
@Entity
@Table(name = "worker")
@DynamicUpdate
class WorkerEntity {

    @Id
    private String id;
    private String name;
    @Enumerated(EnumType.STRING)
    private WorkerStatus status;
    private int concurrencyLimit;
    private String supportedTasks;
    private String pendingCommand;
    private long demoPaceMs;
    private Instant lastHeartbeatAt;

    protected WorkerEntity() {
    }

    public WorkerEntity(String id, String name, int concurrencyLimit, Set<String> supportedTasks, long demoPaceMs,
                        Instant now) {
        this.id = id;
        this.name = name;
        this.status = WorkerStatus.ONLINE;
        this.concurrencyLimit = concurrencyLimit;
        this.supportedTasks = String.join(",", supportedTasks);
        this.demoPaceMs = demoPaceMs;
        this.lastHeartbeatAt = now;
    }

    /** A worker can run a job if it has a plugin for the job's task type. */
    public boolean canRun(JobEntity job) {
        return supportedTaskSet().contains(job.getTaskType());
    }

    public void heartbeat(Instant now) {
        lastHeartbeatAt = now;
    }

    public void changeStatus(WorkerStatus newStatus) {
        status = newStatus;
    }

    /** Adds a command for the next heartbeat; several commands are delivered in order, comma-separated. */
    public void queueCommand(String command) {
        pendingCommand = pendingCommand == null ? command : pendingCommand + "," + command;
    }

    /** Returns and clears the commands waiting for the next heartbeat. */
    public String takeCommand() {
        String command = pendingCommand;
        pendingCommand = null;
        return command;
    }

    public boolean isSilentSince(Instant cutoff) {
        return lastHeartbeatAt.isBefore(cutoff);
    }

    public Set<String> supportedTaskSet() {
        if (supportedTasks == null || supportedTasks.isBlank()) {
            return new LinkedHashSet<>();
        }
        return new LinkedHashSet<>(Arrays.asList(supportedTasks.split(",")));
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public WorkerStatus getStatus() { return status; }
    public int getConcurrencyLimit() { return concurrencyLimit; }
    public String getPendingCommand() { return pendingCommand; }
    public long getDemoPaceMs() { return demoPaceMs; }
    public Instant getLastHeartbeatAt() { return lastHeartbeatAt; }
}

/** Metadata of one task type: what input it takes and its default limits. The code lives in a worker plugin. */
@Entity
@Table(name = "task_definition")
class TaskDefinitionEntity {

    @Id
    private String id;
    private String taskType;
    private String displayName;
    private String description;
    private String inputSchema;
    private int timeoutSeconds;
    private int maxRetries;
    private int defaultPriority;
    private long defaultEstimatedMs;

    protected TaskDefinitionEntity() {
    }

    public TaskDefinitionEntity(String id, TaskManifest manifest, String inputSchemaJson) {
        this.id = id;
        this.taskType = manifest.taskType();
        this.displayName = manifest.displayName();
        this.description = manifest.description();
        this.inputSchema = inputSchemaJson;
        this.timeoutSeconds = manifest.timeoutSeconds();
        this.maxRetries = manifest.maxRetries();
        this.defaultPriority = manifest.defaultPriority();
        this.defaultEstimatedMs = manifest.defaultEstimatedMs();
    }

    public String getId() { return id; }
    public String getTaskType() { return taskType; }
    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
    public String getInputSchema() { return inputSchema; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public int getMaxRetries() { return maxRetries; }
    public int getDefaultPriority() { return defaultPriority; }
    public long getDefaultEstimatedMs() { return defaultEstimatedMs; }
}

/** Groups jobs submitted together as a dependency graph. The jobs carry the actual state. */
@Entity
@Table(name = "workflow")
class WorkflowEntity {

    @Id
    private String id;
    private String name;
    private Instant createdAt;

    protected WorkflowEntity() {
    }

    public WorkflowEntity(String id, String name, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public Instant getCreatedAt() { return createdAt; }
}

/** Metadata of an uploaded input file or a task-produced output file. The bytes are stored under the artifact id. */
@Entity
@Table(name = "artifact")
class ArtifactEntity {

    @Id
    private String id;
    private String jobId;
    private String originalName;
    private String contentType;
    private long sizeBytes;
    private String sha256;
    private Instant createdAt;

    protected ArtifactEntity() {
    }

    public ArtifactEntity(String id, String jobId, String originalName, String contentType,
                          ArtifactStorage.StoredFile stored, Instant createdAt) {
        this.id = id;
        this.jobId = jobId;
        this.originalName = originalName;
        this.contentType = contentType;
        this.sizeBytes = stored.sizeBytes();
        this.sha256 = stored.sha256();
        this.createdAt = createdAt;
    }

    public void linkTo(String jobId) {
        this.jobId = jobId;
    }

    public String getId() { return id; }
    public String getJobId() { return jobId; }
    public String getOriginalName() { return originalName; }
    public String getContentType() { return contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public String getSha256() { return sha256; }
    public Instant getCreatedAt() { return createdAt; }
}

enum EventType {
    JOB_SUBMITTED,
    JOB_RELEASED,
    JOB_CLAIMED,
    JOB_SUCCEEDED,
    JOB_RETRY_SCHEDULED,
    JOB_FAILED,
    JOB_CANCELLED,
    JOB_SKIPPED,
    ATTEMPT_FAILED,
    ATTEMPT_TIMED_OUT,
    LEASE_EXPIRED,
    STALE_UPDATE_REJECTED,
    WORKER_REGISTERED,
    WORKER_OFFLINE,
    WORKER_STATUS_CHANGED,
    FAILURE_SIMULATED,
    TASK_REGISTERED,
    TASK_STATUS_CHANGED,
    WORKFLOW_SUBMITTED,
    SCHEDULER_UPDATED,
    BENCHMARK_IMPORTED
}

/** Append-only history entry shown in the dashboard and in a job's history. */
@Entity
@Table(name = "system_event")
class SystemEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Instant occurredAt;
    @Enumerated(EnumType.STRING)
    private EventType type;
    private String jobId;
    private String workerId;
    private String attemptId;
    private String message;

    protected SystemEventEntity() {
    }

    public SystemEventEntity(Instant occurredAt, EventType type, String jobId, String workerId, String attemptId, String message) {
        this.occurredAt = occurredAt;
        this.type = type;
        this.jobId = jobId;
        this.workerId = workerId;
        this.attemptId = attemptId;
        this.message = message.length() > 1000 ? message.substring(0, 1000) : message;
    }

    public Long getId() { return id; }
    public Instant getOccurredAt() { return occurredAt; }
    public EventType getType() { return type; }
    public String getJobId() { return jobId; }
    public String getWorkerId() { return workerId; }
    public String getAttemptId() { return attemptId; }
    public String getMessage() { return message; }
}

/** A report produced by the separate queuepilot-benchmarks module and imported for display. */
@Entity
@Table(name = "benchmark_report")
class BenchmarkReportEntity {

    @Id
    private String id;
    private String kind;
    private String name;
    private String configJson;
    private String environmentJson;
    private String summaryJson;
    private Instant createdAt;

    protected BenchmarkReportEntity() {
    }

    public BenchmarkReportEntity(String id, String kind, String name, String configJson, String environmentJson,
                                 String summaryJson, Instant createdAt) {
        this.id = id;
        this.kind = kind;
        this.name = name;
        this.configJson = configJson;
        this.environmentJson = environmentJson;
        this.summaryJson = summaryJson;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getKind() { return kind; }
    public String getName() { return name; }
    public String getConfigJson() { return configJson; }
    public String getEnvironmentJson() { return environmentJson; }
    public String getSummaryJson() { return summaryJson; }
    public Instant getCreatedAt() { return createdAt; }
}

/** Single-row table holding the administrator's scheduler configuration, so it survives restarts. */
@Entity
@Table(name = "scheduler_settings")
class SchedulerSettingsEntity {

    public static final int SINGLETON_ID = 1;

    @Id
    private int id;
    @Enumerated(EnumType.STRING)
    private PolicyType activePolicy;
    private int agingIntervalSeconds;
    private int agingStep;

    protected SchedulerSettingsEntity() {
    }

    public void update(PolicyType policy, int agingIntervalSeconds, int agingStep) {
        this.activePolicy = policy;
        this.agingIntervalSeconds = agingIntervalSeconds;
        this.agingStep = agingStep;
    }

    public PolicyType getActivePolicy() { return activePolicy; }
    public int getAgingIntervalSeconds() { return agingIntervalSeconds; }
    public int getAgingStep() { return agingStep; }
}
