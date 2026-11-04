package com.queuepilot.coordinator.attempt;

import com.queuepilot.coordinator.artifact.ArtifactEntity;
import com.queuepilot.coordinator.artifact.ArtifactService;
import com.queuepilot.coordinator.config.JsonCodec;
import com.queuepilot.coordinator.config.QueuePilotProperties;
import com.queuepilot.coordinator.error.Errors.StaleAttemptException;
import com.queuepilot.coordinator.event.EventLog;
import com.queuepilot.coordinator.event.EventType;
import com.queuepilot.coordinator.job.DependencyService;
import com.queuepilot.coordinator.job.JobEntity;
import com.queuepilot.coordinator.job.JobRepository;
import com.queuepilot.coordinator.task.TaskDefinitionEntity;
import com.queuepilot.coordinator.task.TaskRegistryService;
import com.queuepilot.coordinator.worker.WorkerEntity;
import com.queuepilot.coordinator.worker.WorkerRepository;
import com.queuepilot.core.job.AttemptState;
import com.queuepilot.core.job.JobState;
import com.queuepilot.core.job.RetryPolicy;
import com.queuepilot.core.job.WorkerStatus;
import com.queuepilot.core.protocol.WorkerProtocol;
import com.queuepilot.core.protocol.WorkerProtocol.Assignment;
import com.queuepilot.core.protocol.WorkerProtocol.CompletionReport;
import com.queuepilot.core.protocol.WorkerProtocol.FailureReport;
import com.queuepilot.core.protocol.WorkerProtocol.HeartbeatResponse;
import com.queuepilot.core.protocol.WorkerProtocol.ProgressReport;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Everything that happens to an attempt after a job is claimed: creation, lease renewal, progress,
 * completion, failure, expiry and timeout. Every worker update is checked: it is applied only if
 * it names the job's current attempt and comes from that attempt's worker.
 */
@Service
public class AttemptService {

    /** Result of a worker update; STALE becomes HTTP 409 so the worker stops the attempt. */
    public enum UpdateResult { ACCEPTED, STALE }

    private final AttemptRepository attempts;
    private final JobRepository jobs;
    private final WorkerRepository workers;
    private final TaskRegistryService registry;
    private final DependencyService dependencies;
    private final ArtifactService artifacts;
    private final RetryPolicy retryPolicy;
    private final JsonCodec json;
    private final EventLog events;
    private final QueuePilotProperties properties;
    private final Clock clock;

    public AttemptService(AttemptRepository attempts, JobRepository jobs, WorkerRepository workers,
                          TaskRegistryService registry, DependencyService dependencies, ArtifactService artifacts,
                          RetryPolicy retryPolicy, JsonCodec json, EventLog events, QueuePilotProperties properties,
                          Clock clock) {
        this.attempts = attempts;
        this.jobs = jobs;
        this.workers = workers;
        this.registry = registry;
        this.dependencies = dependencies;
        this.artifacts = artifacts;
        this.retryPolicy = retryPolicy;
        this.json = json;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
    }

    /** Larger outputs must be uploaded as file artifacts. */
    static final int MAX_OUTPUT_BYTES = 256 * 1024;

    private Duration lease() {
        return Duration.ofMillis(properties.leaseDurationMs());
    }

    /**
     * Claims {@code jobId} for {@code workerId}. Returns empty if the job is no longer QUEUED.
     * A concurrent claim of the same job fails at commit with an optimistic-lock error.
     */
    @Transactional
    public Optional<Assignment> startAttempt(String jobId, String workerId) {
        JobEntity job = jobs.findById(jobId).orElse(null);
        if (job == null || job.getState() != JobState.QUEUED) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        AttemptEntity attempt = new AttemptEntity(UUID.randomUUID().toString(), jobId, workerId,
                job.getAttemptsUsed() + 1, now, lease());
        job.start(attempt.getId(), workerId, now);
        attempts.save(attempt);
        events.record(EventType.JOB_CLAIMED, jobId, workerId, attempt.getId(),
                "Job " + job.label() + " attempt " + attempt.getAttemptNumber() + " assigned to worker " + workerId);

        return Optional.of(new Assignment(attempt.getId(), attempt.getAttemptNumber(), jobId, job.getTaskType(),
                json.readMap(job.getInputJson()), task(job).getTimeoutSeconds()));
    }

    /** The task definition of a job; timeouts and retry limits are read from it. */
    private TaskDefinitionEntity task(JobEntity job) {
        return registry.find(job.getTaskType()).orElseThrow();
    }

    /** Renews the leases of the worker's attempts that are still current; tells it which ones to stop. */
    @Transactional
    public HeartbeatResponse heartbeat(String workerId, List<String> attemptIds) {
        WorkerEntity worker = workers.findById(workerId).orElse(null);
        List<String> renewed = new ArrayList<>();
        List<String> revoked = new ArrayList<>();
        List<String> active = attemptIds == null ? List.of() : attemptIds;
        if (worker == null || worker.getStatus() == WorkerStatus.OFFLINE) {
            revoked.addAll(active);
            return new HeartbeatResponse(renewed, revoked, WorkerProtocol.COMMAND_REREGISTER);
        }
        Instant now = clock.instant();
        worker.heartbeat(now);
        for (String attemptId : active) {
            AttemptEntity attempt = attempts.findById(attemptId).orElse(null);
            if (attempt != null && attempt.accepts(workerId)) {
                attempt.renewLease(now, lease());
                renewed.add(attemptId);
            } else {
                revoked.add(attemptId);
            }
        }
        return new HeartbeatResponse(renewed, revoked, worker.takeCommand());
    }

    @Transactional
    public UpdateResult progress(String workerId, String attemptId, ProgressReport report) {
        AttemptEntity attempt = currentAttempt(workerId, attemptId);
        if (attempt == null) {
            return UpdateResult.STALE;
        }
        attempt.renewLease(clock.instant(), lease());
        String message = report.message() == null ? "" : report.message();
        jobs.updateProgress(attempt.getJobId(), attemptId, Math.max(0, Math.min(100, report.percent())),
                message.length() > 500 ? message.substring(0, 500) : message);
        return UpdateResult.ACCEPTED;
    }

    @Transactional
    public UpdateResult complete(String workerId, String attemptId, CompletionReport report) {
        AttemptEntity attempt = currentAttempt(workerId, attemptId);
        if (attempt == null) {
            return UpdateResult.STALE;
        }
        JobEntity job = jobs.findById(attempt.getJobId()).orElseThrow();
        Instant now = clock.instant();
        String outputJson = json.write(report.output() == null ? Map.of() : report.output());
        if (outputJson.getBytes(StandardCharsets.UTF_8).length > MAX_OUTPUT_BYTES) {
            endAttempt(attempt, job, AttemptState.FAILED, "OUTPUT_TOO_LARGE",
                    "Output exceeds " + MAX_OUTPUT_BYTES + " bytes", false, now);
            return UpdateResult.ACCEPTED;
        }
        List<String> outputIds = report.outputArtifactIds() == null ? List.of() : report.outputArtifactIds();
        for (String id : outputIds) {
            ArtifactEntity artifact = artifacts.get(id);
            if (!job.getId().equals(artifact.getJobId())) {
                endAttempt(attempt, job, AttemptState.FAILED, "INVALID_OUTPUT_ARTIFACT",
                        "Artifact " + id + " is not an output of this job", false, now);
                return UpdateResult.ACCEPTED;
            }
        }
        attempt.finish(AttemptState.SUCCEEDED, null, now);
        job.succeed(outputJson, String.join(",", outputIds), now);
        events.record(EventType.JOB_SUCCEEDED, job.getId(), workerId, attemptId,
                "Job " + job.label() + " succeeded on attempt " + attempt.getAttemptNumber());
        dependencies.onJobFinished(job, now);
        return UpdateResult.ACCEPTED;
    }

    @Transactional
    public UpdateResult fail(String workerId, String attemptId, FailureReport report) {
        AttemptEntity attempt = currentAttempt(workerId, attemptId);
        if (attempt == null) {
            return UpdateResult.STALE;
        }
        JobEntity job = jobs.findById(attempt.getJobId()).orElseThrow();
        String code = report.errorCode() == null || report.errorCode().isBlank() ? "TASK_ERROR" : report.errorCode();
        AttemptState outcome = "TIMEOUT".equals(code) ? AttemptState.TIMED_OUT : AttemptState.FAILED;
        endAttempt(attempt, job, outcome, code, report.message(), report.retryable(), clock.instant());
        return UpdateResult.ACCEPTED;
    }

    /** Recovery: the attempt's lease ran out without a heartbeat. */
    @Transactional
    public void expireLeaseIfDue(String attemptId) {
        AttemptEntity attempt = attempts.findById(attemptId).orElse(null);
        Instant now = clock.instant();
        if (attempt == null || !attempt.leaseExpired(now)) {
            return;
        }
        JobEntity job = jobs.findById(attempt.getJobId()).orElseThrow();
        events.record(EventType.LEASE_EXPIRED, job.getId(), attempt.getWorkerId(), attemptId,
                "Lease of attempt " + attempt.getAttemptNumber() + " for " + job.label() + " expired");
        endAttempt(attempt, job, AttemptState.LEASE_EXPIRED, "LEASE_EXPIRED",
                "No heartbeat from worker " + attempt.getWorkerId() + " before the lease ended", true, now);
    }

    /** Recovery: the attempt ran longer than the task's timeout (F18). The worker learns this via a revoked heartbeat. */
    @Transactional
    public void timeOutIfExceeded(String attemptId) {
        AttemptEntity attempt = attempts.findById(attemptId).orElse(null);
        if (attempt == null) {
            return;
        }
        JobEntity job = jobs.findById(attempt.getJobId()).orElseThrow();
        Instant now = clock.instant();
        int timeoutSeconds = task(job).getTimeoutSeconds();
        if (attempt.exceededTimeout(now, timeoutSeconds)) {
            events.record(EventType.ATTEMPT_TIMED_OUT, job.getId(), attempt.getWorkerId(), attemptId,
                    "Attempt " + attempt.getAttemptNumber() + " of " + job.label() + " exceeded " + timeoutSeconds + "s");
            endAttempt(attempt, job, AttemptState.TIMED_OUT, "TIMEOUT",
                    "Exceeded the task timeout of " + timeoutSeconds + "s", true, now);
        }
    }

    /** Ends every running attempt of a worker that went offline, deregistered or was killed. */
    @Transactional
    public void abandonAttemptsOf(String workerId, String reason) {
        Instant now = clock.instant();
        for (AttemptEntity attempt : attempts.findByWorkerIdAndState(workerId, AttemptState.RUNNING)) {
            JobEntity job = jobs.findById(attempt.getJobId()).orElseThrow();
            endAttempt(attempt, job, AttemptState.ABANDONED, "WORKER_LOST", reason, true, now);
        }
    }

    /**
     * Closes an attempt and decides the job's fate: retry after a backoff delay if the failure is
     * retryable and retries remain, otherwise FAILED (which also skips dependants).
     */
    private void endAttempt(AttemptEntity attempt, JobEntity job, AttemptState outcome, String code, String message,
                            boolean retryable, Instant now) {
        String error = message == null || message.isBlank() ? code : code + ": " + message;
        error = error.length() > 2000 ? error.substring(0, 2000) : error;
        attempt.finish(outcome, error, now);
        if (!job.isCurrentAttempt(attempt.getId())) {
            return;
        }
        int maxRetries = task(job).getMaxRetries();
        if (retryable && job.canRetry(maxRetries)) {
            Duration delay = retryPolicy.delayBefore(job.getAttemptsUsed());
            job.retryLater(now.plus(delay), error, maxRetries);
            events.record(EventType.JOB_RETRY_SCHEDULED, job.getId(), attempt.getWorkerId(), attempt.getId(),
                    "Job " + job.label() + " attempt " + attempt.getAttemptNumber() + " ended " + outcome
                            + " (" + code + "); retry " + job.getAttemptsUsed() + "/" + maxRetries
                            + " in " + delay.toMillis() + " ms");
        } else {
            job.fail(error, now);
            events.record(EventType.JOB_FAILED, job.getId(), attempt.getWorkerId(), attempt.getId(),
                    "Job " + job.label() + " failed: " + code + (retryable ? " (retries exhausted)" : " (not retryable)"));
            dependencies.onJobFinished(job, now);
        }
    }

    /** Returns the attempt only if it is the job's current attempt and belongs to this worker. */
    private AttemptEntity currentAttempt(String workerId, String attemptId) {
        AttemptEntity attempt = attempts.findById(attemptId).orElse(null);
        JobEntity job = attempt == null ? null : jobs.findById(attempt.getJobId()).orElse(null);
        if (attempt == null || job == null || !attempt.accepts(workerId) || !job.isCurrentAttempt(attemptId)) {
            events.record(EventType.STALE_UPDATE_REJECTED, job == null ? null : job.getId(), workerId, attemptId,
                    "Rejected update for attempt " + attemptId + " from worker " + workerId + ": not the current attempt");
            return null;
        }
        return attempt;
    }

    /** Output upload from a worker, checked like every other attempt update. */
    @Transactional
    public ArtifactEntity uploadOutput(String workerId, String attemptId, MultipartFile file) {
        AttemptEntity attempt = currentAttempt(workerId, attemptId);
        if (attempt == null) {
            throw new StaleAttemptException("Attempt " + attemptId + " is not current");
        }
        return artifacts.uploadOutput(file, attempt.getJobId());
    }

    /** File download for a worker: only files of the attempt's own job. */
    @Transactional(readOnly = true)
    public ArtifactEntity inputForAttempt(String workerId, String attemptId, String artifactId) {
        AttemptEntity attempt = attempts.findById(attemptId).orElse(null);
        if (attempt == null || !attempt.accepts(workerId)) {
            throw new StaleAttemptException("Attempt " + attemptId + " is not current");
        }
        ArtifactEntity artifact = artifacts.get(artifactId);
        if (!attempt.getJobId().equals(artifact.getJobId())) {
            throw new StaleAttemptException("Artifact " + artifactId + " does not belong to this attempt's job");
        }
        return artifact;
    }
}
