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
import com.queuepilot.coordinator.security.TokenHasher;
import com.queuepilot.coordinator.task.ExecutorKind;
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
import com.queuepilot.core.protocol.WorkerProtocol.AttemptRef;
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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Everything that happens to an attempt after a job is claimed: creation, lease renewal, progress,
 * completion, failure, expiry and timeout. Every worker update is <b>fenced</b>: it is applied only
 * if it names the job's current attempt, comes from that attempt's worker and carries its token.
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
        String token = TokenHasher.newToken();
        AttemptEntity attempt = new AttemptEntity(UUID.randomUUID().toString(), jobId, workerId,
                job.getAttemptsUsed() + 1, token, now, lease());
        job.start(attempt.getId(), workerId, now);
        attempts.save(attempt);
        events.record(EventType.JOB_CLAIMED, jobId, workerId, attempt.getId(),
                "Job " + job.getName() + " attempt " + attempt.getAttemptNumber() + " assigned to worker " + workerId);

        TaskDefinitionEntity task = registry.get(job.getTaskDefinitionId());
        WorkerProtocol.ContainerSpec container = null;
        if (task.getExecutorKind() == ExecutorKind.CONTAINER) {
            List<String> command = Arrays.asList(json.read(task.getContainerCommand(), String[].class));
            container = new WorkerProtocol.ContainerSpec(task.getContainerImage(), command, task.getCpuMillis(), task.getMemoryMb());
        }
        return Optional.of(new Assignment(attempt.getId(), token, attempt.getAttemptNumber(), jobId,
                job.getTaskType(), job.getTaskVersion(), task.getExecutorKind().name(), json.readMap(job.getInputJson()),
                artifacts.inputIdsForJob(jobId), properties.leaseDurationMs(), job.getTimeoutSeconds(), container));
    }

    /** Renews the leases of the worker's attempts that are still current; tells it which ones to stop. */
    @Transactional
    public HeartbeatResponse heartbeat(String workerId, List<AttemptRef> refs) {
        WorkerEntity worker = workers.findById(workerId).orElseThrow(() -> new StaleAttemptException("Unknown worker"));
        List<String> renewed = new ArrayList<>();
        List<String> revoked = new ArrayList<>();
        List<AttemptRef> active = refs == null ? List.of() : refs;
        if (worker.getStatus() == WorkerStatus.OFFLINE) {
            active.forEach(ref -> revoked.add(ref.attemptId()));
            return new HeartbeatResponse(renewed, revoked, WorkerProtocol.COMMAND_REREGISTER);
        }
        Instant now = clock.instant();
        worker.heartbeat(now);
        for (AttemptRef ref : active) {
            AttemptEntity attempt = attempts.findById(ref.attemptId()).orElse(null);
            if (attempt != null && attempt.accepts(workerId, ref.fencingToken())) {
                attempt.renewLease(now, lease());
                renewed.add(attempt.getId());
            } else {
                revoked.add(ref.attemptId());
            }
        }
        return new HeartbeatResponse(renewed, revoked, worker.takeCommand());
    }

    @Transactional
    public UpdateResult progress(String workerId, String attemptId, ProgressReport report) {
        AttemptEntity attempt = currentAttempt(workerId, attemptId, report.fencingToken());
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
        AttemptEntity attempt = currentAttempt(workerId, attemptId, report.fencingToken());
        if (attempt == null) {
            return UpdateResult.STALE;
        }
        JobEntity job = jobs.findById(attempt.getJobId()).orElseThrow();
        Instant now = clock.instant();
        String outputJson = json.write(report.output() == null ? Map.of() : report.output());
        if (outputJson.getBytes(StandardCharsets.UTF_8).length > properties.maxOutputBytes()) {
            endAttempt(attempt, job, AttemptState.FAILED, "OUTPUT_TOO_LARGE",
                    "Output exceeds " + properties.maxOutputBytes() + " bytes", false, now);
            return UpdateResult.ACCEPTED;
        }
        List<String> outputIds = report.outputArtifactIds() == null ? List.of() : report.outputArtifactIds();
        for (String id : outputIds) {
            ArtifactEntity artifact = artifacts.get(id);
            if (!job.getId().equals(artifact.getJobId()) || !ArtifactEntity.ROLE_OUTPUT.equals(artifact.getRole())) {
                endAttempt(attempt, job, AttemptState.FAILED, "INVALID_OUTPUT_ARTIFACT",
                        "Artifact " + id + " is not an output of this job", false, now);
                return UpdateResult.ACCEPTED;
            }
        }
        attempt.finish(AttemptState.SUCCEEDED, null, null, now);
        job.succeed(outputJson, String.join(",", outputIds), now);
        events.record(EventType.JOB_SUCCEEDED, job.getId(), workerId, attemptId,
                "Job " + job.getName() + " succeeded on attempt " + attempt.getAttemptNumber());
        dependencies.onJobFinished(job, now);
        return UpdateResult.ACCEPTED;
    }

    @Transactional
    public UpdateResult fail(String workerId, String attemptId, FailureReport report) {
        AttemptEntity attempt = currentAttempt(workerId, attemptId, report.fencingToken());
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
                "Lease of attempt " + attempt.getAttemptNumber() + " for " + job.getName() + " expired");
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
        if (attempt.exceededTimeout(now, job.getTimeoutSeconds())) {
            events.record(EventType.ATTEMPT_TIMED_OUT, job.getId(), attempt.getWorkerId(), attemptId,
                    "Attempt " + attempt.getAttemptNumber() + " of " + job.getName() + " exceeded " + job.getTimeoutSeconds() + "s");
            endAttempt(attempt, job, AttemptState.TIMED_OUT, "TIMEOUT",
                    "Exceeded the task timeout of " + job.getTimeoutSeconds() + "s", true, now);
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
        attempt.finish(outcome, code, message, now);
        if (!job.isCurrentAttempt(attempt.getId())) {
            return;
        }
        if (retryable && job.canRetry()) {
            Duration delay = retryPolicy.delayBefore(job.getAttemptsUsed());
            job.retryLater(now.plus(delay), code, message);
            events.record(EventType.JOB_RETRY_SCHEDULED, job.getId(), attempt.getWorkerId(), attempt.getId(),
                    "Job " + job.getName() + " attempt " + attempt.getAttemptNumber() + " ended " + outcome
                            + " (" + code + "); retry " + job.getAttemptsUsed() + "/" + job.getMaxRetries()
                            + " in " + delay.toMillis() + " ms");
        } else {
            job.fail(code, message, now);
            events.record(EventType.JOB_FAILED, job.getId(), attempt.getWorkerId(), attempt.getId(),
                    "Job " + job.getName() + " failed: " + code + (retryable ? " (retries exhausted)" : " (not retryable)"));
            dependencies.onJobFinished(job, now);
        }
    }

    /** Returns the attempt only if it is the job's current attempt and the caller proves ownership. */
    private AttemptEntity currentAttempt(String workerId, String attemptId, String token) {
        AttemptEntity attempt = attempts.findById(attemptId).orElse(null);
        JobEntity job = attempt == null ? null : jobs.findById(attempt.getJobId()).orElse(null);
        if (attempt == null || job == null || !attempt.accepts(workerId, token) || !job.isCurrentAttempt(attemptId)) {
            events.record(EventType.STALE_UPDATE_REJECTED, job == null ? null : job.getId(), workerId, attemptId,
                    "Rejected update for attempt " + attemptId + " from worker " + workerId + ": not the current attempt");
            return null;
        }
        return attempt;
    }

    /** Output upload from a worker, fenced like every other attempt update. */
    @Transactional
    public ArtifactEntity uploadOutput(String workerId, String attemptId, String token, MultipartFile file) {
        AttemptEntity attempt = currentAttempt(workerId, attemptId, token);
        if (attempt == null) {
            throw new StaleAttemptException("Attempt " + attemptId + " is not current");
        }
        return artifacts.uploadOutput(file, attempt.getJobId());
    }

    /** Input download for a worker: only input artifacts of the attempt's own job. */
    @Transactional(readOnly = true)
    public ArtifactEntity inputForAttempt(String workerId, String attemptId, String token, String artifactId) {
        AttemptEntity attempt = attempts.findById(attemptId).orElse(null);
        if (attempt == null || !attempt.accepts(workerId, token)) {
            throw new StaleAttemptException("Attempt " + attemptId + " is not current");
        }
        ArtifactEntity artifact = artifacts.get(artifactId);
        if (!attempt.getJobId().equals(artifact.getJobId()) || !ArtifactEntity.ROLE_INPUT.equals(artifact.getRole())) {
            throw new StaleAttemptException("Artifact " + artifactId + " is not an input of this attempt's job");
        }
        return artifact;
    }
}
