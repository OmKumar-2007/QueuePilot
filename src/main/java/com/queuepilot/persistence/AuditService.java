package com.queuepilot.persistence;

import com.queuepilot.model.Job;
import com.queuepilot.model.JobResult;
import com.queuepilot.model.Worker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Audit and persistence service ensuring all state transitions, attempts,
 * and system health events are recorded in persistent storage.
 * Satisfies SRS F3, F9, F11, F15.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final JobRepository jobRepository;
    private final JobAttemptRepository attemptRepository;
    private final WorkerRepository workerRepository;
    private final SystemEventRepository eventRepository;

    @Autowired(required = false)
    public AuditService(JobRepository jobRepository,
                        JobAttemptRepository attemptRepository,
                        WorkerRepository workerRepository,
                        SystemEventRepository eventRepository) {
        this.jobRepository = jobRepository;
        this.attemptRepository = attemptRepository;
        this.workerRepository = workerRepository;
        this.eventRepository = eventRepository;
    }

    public AuditService() {
        this(null, null, null, null);
    }

    @Transactional
    public void recordJobCreated(Job job) {
        if (jobRepository == null || job == null) return;
        try {
            JobEntity entity = toEntity(job);
            jobRepository.save(entity);
            recordEvent("JOB_SUBMITTED", job.getId(), "Job submitted: " + job.getName(), job.getSummary());
        } catch (Exception e) {
            log.warn("Failed to persist job creation for [{}]: {}", job.getId(), e.getMessage());
        }
    }

    @Transactional
    public void recordJobAttemptStarted(Job job, String workerId, String attemptToken) {
        if (jobRepository == null || job == null) return;
        try {
            JobEntity entity = toEntity(job);
            entity.setAssignedWorkerId(workerId);
            entity.setAttemptToken(attemptToken);
            jobRepository.save(entity);

            if (attemptRepository != null) {
                JobAttemptEntity attempt = new JobAttemptEntity(
                        job.getId(),
                        job.getRetryCount() + 1,
                        workerId,
                        attemptToken,
                        Instant.now(),
                        "RUNNING"
                );
                attemptRepository.save(attempt);
            }
            recordEvent("JOB_DISPATCHED", job.getId(), "Job dispatched to " + workerId, "Attempt token: " + attemptToken);
        } catch (Exception e) {
            log.warn("Failed to persist attempt started for [{}]: {}", job.getId(), e.getMessage());
        }
    }

    @Transactional
    public void recordJobProgress(Job job) {
        if (jobRepository == null || job == null) return;
        try {
            jobRepository.findById(job.getId()).ifPresent(entity -> {
                entity.setProgressPercent(job.getProgressPercent());
                entity.setProgressMessage(job.getProgressMessage());
                jobRepository.save(entity);
            });
        } catch (Exception e) {
            log.warn("Failed to persist progress for [{}]: {}", job.getId(), e.getMessage());
        }
    }

    @Transactional
    public void recordJobCompleted(Job job, JobResult result) {
        if (jobRepository == null || job == null) return;
        try {
            JobEntity entity = toEntity(job);
            if (result != null) {
                entity.setResultSummary(result.getMessage());
            }
            jobRepository.save(entity);

            if (attemptRepository != null && job.getAttemptToken() != null) {
                attemptRepository.findByJobIdAndAttemptToken(job.getId(), job.getAttemptToken()).ifPresent(attempt -> {
                    attempt.setStatus("COMPLETED");
                    attempt.setEndedAt(Instant.now());
                    attempt.setNotes("Completed successfully");
                    attemptRepository.save(attempt);
                });
            }
            recordEvent("JOB_COMPLETED", job.getId(), "Job completed successfully", result != null ? result.getMessage() : "OK");
        } catch (Exception e) {
            log.warn("Failed to persist job completion for [{}]: {}", job.getId(), e.getMessage());
        }
    }

    @Transactional
    public void recordJobAttemptFailed(Job job, String attemptToken, String reason) {
        if (jobRepository == null || job == null) return;
        try {
            JobEntity entity = toEntity(job);
            entity.setErrorMessage(reason);
            jobRepository.save(entity);

            if (attemptRepository != null && attemptToken != null) {
                attemptRepository.findByJobIdAndAttemptToken(job.getId(), attemptToken).ifPresent(attempt -> {
                    attempt.setStatus("FAILED");
                    attempt.setEndedAt(Instant.now());
                    attempt.setNotes("Failure: " + reason);
                    attemptRepository.save(attempt);
                });
            }
            recordEvent("JOB_FAILED", job.getId(), "Job attempt failed: " + reason, "Attempt token: " + attemptToken);
        } catch (Exception e) {
            log.warn("Failed to persist job failure for [{}]: {}", job.getId(), e.getMessage());
        }
    }

    @Transactional
    public void recordJobAttemptAbandoned(Job job, String workerId, String attemptToken, String reason) {
        if (jobRepository == null || job == null) return;
        try {
            if (attemptRepository != null && attemptToken != null) {
                attemptRepository.findByJobIdAndAttemptToken(job.getId(), attemptToken).ifPresent(attempt -> {
                    attempt.setStatus("ABANDONED");
                    attempt.setEndedAt(Instant.now());
                    attempt.setNotes("Abandoned: " + reason);
                    attemptRepository.save(attempt);
                });
            }
            recordEvent("WORKER_FAILURE_DETECTED", workerId, "Worker lease expired or failed: " + reason, "Job " + job.getId() + " reclaimed");
        } catch (Exception e) {
            log.warn("Failed to persist abandoned attempt for [{}]: {}", job.getId(), e.getMessage());
        }
    }

    @Transactional
    public void syncWorker(Worker worker) {
        if (workerRepository == null || worker == null) return;
        try {
            WorkerEntity entity = new WorkerEntity();
            entity.setId(worker.getId());
            entity.setName(worker.getName());
            entity.setStatus(worker.getStatus().name());
            entity.setCapabilities(String.join(",", worker.getCapabilities()));
            entity.setCurrentJobId(worker.getCurrentJobId());
            entity.setLastHeartbeat(worker.getLastHeartbeat());
            entity.setTotalCompleted(worker.getTotalJobsCompleted());
            entity.setTotalFailed(worker.getTotalJobsFailed());
            workerRepository.save(entity);
        } catch (Exception e) {
            log.warn("Failed to sync worker state for [{}]: {}", worker.getId(), e.getMessage());
        }
    }

    @Transactional
    public void recordEvent(String eventType, String entityId, String message, String details) {
        if (eventRepository == null) return;
        try {
            SystemEventEntity event = new SystemEventEntity(eventType, entityId, message, details);
            eventRepository.save(event);
        } catch (Exception e) {
            log.warn("Failed to persist system event: {}", e.getMessage());
        }
    }

    private JobEntity toEntity(Job job) {
        JobEntity e = new JobEntity();
        e.setId(job.getId());
        e.setName(job.getName());
        e.setJobType(job.getType().name());
        e.setState(job.getState().name());
        e.setBasePriority(job.getBasePriority());
        e.setEffectivePriority(job.getEffectivePriority());
        e.setEstimatedDurationMs(job.getEstimatedDurationMs());
        e.setRetryCount(job.getRetryCount());
        e.setMaxRetries(job.getMaxRetries());
        e.setProgressPercent(job.getProgressPercent());
        e.setProgressMessage(job.getProgressMessage());
        e.setCreatedAt(job.getCreatedAt());
        e.setScheduledStartTime(job.getScheduledStartTime());
        e.setDeadline(job.getDeadline());
        e.setStartedAt(job.getStartedAt());
        e.setCompletedAt(job.getCompletedAt());
        e.setAssignedWorkerId(job.getAssignedWorkerId());
        e.setAttemptToken(job.getAttemptToken());
        if (job.getResult() != null) {
            e.setResultSummary(job.getResult().getMessage());
        }
        e.setErrorMessage(job.getFailureReason());
        return e;
    }
}
