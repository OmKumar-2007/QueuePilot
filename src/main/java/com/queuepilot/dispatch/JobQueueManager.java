package com.queuepilot.dispatch;

import com.queuepilot.dag.CycleDetectedException;
import com.queuepilot.dag.DependencyGraph;
import com.queuepilot.model.Job;
import com.queuepilot.model.JobResult;
import com.queuepilot.model.JobState;
import com.queuepilot.model.Worker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe central manager for logical job queues, DAG transitions,
 * attempt tokens, and lease synchronization.
 * Satisfies SRS F3, F5, F11, F12.
 */
@Component
public class JobQueueManager {

    private static final Logger log = LoggerFactory.getLogger(JobQueueManager.class);

    private final Map<String, Job> jobStore = new ConcurrentHashMap<>();
    private final DependencyGraph dependencyGraph = new DependencyGraph();
    private final ReentrantLock queueLock = new ReentrantLock();
    private final com.queuepilot.persistence.AuditService auditService;

    public JobQueueManager(com.queuepilot.persistence.AuditService auditService) {
        this.auditService = auditService;
    }

    public JobQueueManager() {
        this(null);
    }

    /**
     * Submits and registers a new Job into the system with validation and DAG check.
     */
    public void submitJob(Job job) throws CycleDetectedException {
        if (job == null) {
            throw new IllegalArgumentException("Job cannot be null");
        }

        queueLock.lock();
        try {
            // Register in DAG and check cycles
            dependencyGraph.addJob(job);
            dependencyGraph.validateNoCycles();

            Instant now = Instant.now();
            if (job.getScheduledStartTime() != null && job.getScheduledStartTime().isAfter(now)) {
                job.markScheduled(job.getScheduledStartTime());
            } else if (!dependencyGraph.isJobRunnable(job, jobStore, now)) {
                job.markBlocked();
            } else {
                job.markQueued();
            }

            jobStore.put(job.getId(), job);
            if (auditService != null) {
                auditService.recordJobCreated(job);
            }
            log.info("Job [{}] submitted in state [{}] (Priority: {}, Est: {}ms)", 
                    job.getId(), job.getState(), job.getEffectivePriority(), job.getEstimatedDurationMs());

        } finally {
            queueLock.unlock();
        }
    }

    /**
     * Returns a snapshot of all currently runnable (QUEUED) jobs.
     */
    public List<Job> getRunnableJobs() {
        queueLock.lock();
        try {
            Instant now = Instant.now();
            List<Job> runnable = new ArrayList<>();

            for (Job job : jobStore.values()) {
                if (job.getState() == JobState.SCHEDULED && job.getScheduledStartTime() != null && !job.getScheduledStartTime().isAfter(now)) {
                    if (dependencyGraph.isJobRunnable(job, jobStore, now)) {
                        job.markQueued();
                    } else {
                        job.markBlocked();
                    }
                }

                if (job.getState() == JobState.QUEUED) {
                    runnable.add(job);
                }
            }
            return runnable;
        } finally {
            queueLock.unlock();
        }
    }

    /**
     * Atomically claims a runnable job for a worker, generating an attempt token and time-limited lease.
     */
    public Optional<String> claimJob(Job job, Worker worker, long leaseDurationMs) {
        queueLock.lock();
        try {
            if (job.getState() != JobState.QUEUED) {
                return Optional.empty();
            }
            if (!worker.canExecute(job)) {
                return Optional.empty();
            }

            String attemptToken = UUID.randomUUID().toString();
            Instant leaseExpiry = Instant.now().plusMillis(leaseDurationMs);

            job.markRunning(worker.getId(), attemptToken, leaseExpiry);
            if (auditService != null) {
                auditService.recordJobAttemptStarted(job, worker.getId(), attemptToken);
            }
            log.info("Job [{}] claimed by worker [{}] with attempt token [{}] until [{}]",
                    job.getId(), worker.getId(), attemptToken, leaseExpiry);

            return Optional.of(attemptToken);
        } finally {
            queueLock.unlock();
        }
    }

    /**
     * Updates live execution progress for an active attempt.
     */
    public boolean updateProgress(String jobId, String attemptToken, int progressPercent, String message) {
        Job job = jobStore.get(jobId);
        if (job == null) return false;

        synchronized (job) {
            if (job.getState() == JobState.RUNNING && attemptToken.equals(job.getAttemptToken())) {
                job.updateProgress(progressPercent, message);
                if (auditService != null) {
                    auditService.recordJobProgress(job);
                }
                return true;
            }
            log.warn("Rejected stale progress update for job [{}] with token [{}]", jobId, attemptToken);
            return false;
        }
    }

    /**
     * Renews the lease for an active attempt upon receiving worker heartbeat.
     */
    public boolean renewLease(String jobId, String attemptToken, long leaseExtensionMs) {
        Job job = jobStore.get(jobId);
        if (job == null) return false;

        synchronized (job) {
            if (job.getState() == JobState.RUNNING && attemptToken.equals(job.getAttemptToken())) {
                job.renewLease(Instant.now().plusMillis(leaseExtensionMs));
                return true;
            }
            return false;
        }
    }

    /**
     * Completes an active job. Fences against stale tokens and unlocks dependent jobs in DAG.
     */
    public boolean completeJob(String jobId, String attemptToken, JobResult result) {
        queueLock.lock();
        try {
            Job job = jobStore.get(jobId);
            if (job == null) return false;

            if (job.getState() != JobState.RUNNING || !attemptToken.equals(job.getAttemptToken())) {
                log.warn("Fencing rejected stale completion for job [{}] from token [{}]", jobId, attemptToken);
                return false;
            }

            job.markCompleted(result);
            if (auditService != null) {
                auditService.recordJobCompleted(job, result);
            }
            log.info("Job [{}] marked COMPLETED. Evaluating dependent DAG jobs...", jobId);

            // Unblock dependent jobs whose prerequisites are now satisfied
            unlockDependentJobs();

            return true;
        } finally {
            queueLock.unlock();
        }
    }

    /**
     * Records failure or abandonment of a job attempt. Handles bounded retry logic.
     */
    public boolean failJob(String jobId, String attemptToken, String reason) {
        queueLock.lock();
        try {
            Job job = jobStore.get(jobId);
            if (job == null) return false;

            if (!attemptToken.equals(job.getAttemptToken())) {
                log.warn("Fencing rejected stale failure for job [{}] from token [{}]", jobId, attemptToken);
                return false;
            }

            job.incrementRetryCount();
            if (job.canRetry()) {
                log.info("Job [{}] failed attempt ({}/{}). Re-queuing for retry: {}",
                        jobId, job.getRetryCount(), job.getMaxRetries(), reason);
                job.markQueued();
            } else {
                log.error("Job [{}] exhausted retries ({}/{}). Marking FAILED: {}",
                        jobId, job.getRetryCount(), job.getMaxRetries(), reason);
                job.markFailed(reason);
            }
            if (auditService != null) {
                auditService.recordJobAttemptFailed(job, attemptToken, reason);
            }
            return true;
        } finally {
            queueLock.unlock();
        }
    }

    /**
     * Cancels a job before or during execution.
     */
    public boolean cancelJob(String jobId, String reason) {
        queueLock.lock();
        try {
            Job job = jobStore.get(jobId);
            if (job == null || job.getState().isTerminal()) {
                return false;
            }
            job.markCancelled(reason);
            log.info("Job [{}] marked CANCELLED: {}", jobId, reason);
            return true;
        } finally {
            queueLock.unlock();
        }
    }

    private void unlockDependentJobs() {
        Instant now = Instant.now();
        for (Job candidate : jobStore.values()) {
            if (candidate.getState() == JobState.BLOCKED) {
                if (dependencyGraph.isJobRunnable(candidate, jobStore, now)) {
                    candidate.markQueued();
                    log.info("Dependent job [{}] is now unblocked and QUEUED.", candidate.getId());
                }
            }
        }
    }

    public Optional<Job> getJob(String jobId) {
        return Optional.ofNullable(jobStore.get(jobId));
    }

    public List<Job> getAllJobs() {
        return Collections.unmodifiableList(new ArrayList<>(jobStore.values()));
    }

    public void clear() {
        queueLock.lock();
        try {
            jobStore.clear();
            dependencyGraph.clear();
        } finally {
            queueLock.unlock();
        }
    }
}
