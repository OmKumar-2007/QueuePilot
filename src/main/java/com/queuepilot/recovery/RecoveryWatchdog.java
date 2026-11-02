package com.queuepilot.recovery;

import com.queuepilot.dispatch.DispatchCoordinator;
import com.queuepilot.dispatch.JobQueueManager;
import com.queuepilot.model.Job;
import com.queuepilot.model.JobState;
import com.queuepilot.model.Worker;
import com.queuepilot.model.WorkerStatus;
import com.queuepilot.persistence.AuditService;
import com.queuepilot.worker.WorkerInstance;
import com.queuepilot.worker.WorkerPool;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Background Recovery Watchdog for fault detection and automated task recovery.
 * Monitors worker heartbeats and time-limited assignment leases.
 * Satisfies SRS F10, F11, U5, and Demo Scenario Steps 5 & 6.
 */
@Component
public class RecoveryWatchdog implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(RecoveryWatchdog.class);

    private final WorkerPool workerPool;
    private final JobQueueManager jobQueueManager;
    private final AuditService auditService;
    private final DispatchCoordinator dispatchCoordinator;

    @Value("${queuepilot.recovery.heartbeat-timeout-ms:4000}")
    private long heartbeatTimeoutMs = 4000;

    @Value("${queuepilot.recovery.watchdog-interval-ms:1000}")
    private long watchdogIntervalMs = 1000;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger totalFailuresDetected = new AtomicInteger(0);
    private final AtomicInteger totalJobsRecovered = new AtomicInteger(0);

    private Thread watchdogThread;

    @Autowired
    public RecoveryWatchdog(WorkerPool workerPool,
                            JobQueueManager jobQueueManager,
                            AuditService auditService,
                            DispatchCoordinator dispatchCoordinator) {
        this.workerPool = workerPool;
        this.jobQueueManager = jobQueueManager;
        this.auditService = auditService;
        this.dispatchCoordinator = dispatchCoordinator;
    }

    @PostConstruct
    public synchronized void start() {
        if (!running.get()) {
            running.set(true);
            watchdogThread = new Thread(this, "recovery-watchdog-thread");
            watchdogThread.setDaemon(true);
            watchdogThread.start();
            log.info("RecoveryWatchdog started (Heartbeat timeout: {}ms, Check interval: {}ms).",
                    heartbeatTimeoutMs, watchdogIntervalMs);
        }
    }

    @PreDestroy
    public synchronized void stop() {
        running.set(false);
        if (watchdogThread != null) {
            watchdogThread.interrupt();
        }
    }

    @Override
    public void run() {
        while (running.get()) {
            try {
                checkAndRecover();
                Thread.sleep(watchdogIntervalMs);
            } catch (InterruptedException e) {
                if (!running.get()) break;
            } catch (Exception e) {
                log.error("Error during recovery watchdog cycle: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * Executes one scan over workers and active job leases.
     * Can also be called on-demand in tests.
     */
    public synchronized void checkAndRecover() {
        Instant now = Instant.now();
        boolean triggeredRecovery = false;

        // 1. Scan Workers for missed heartbeats or dead status
        for (WorkerInstance instance : workerPool.getAllWorkerInstances()) {
            Worker worker = instance.getWorkerModel();
            boolean isDead = (worker.getStatus() == WorkerStatus.DEAD);
            boolean timedOut = false;

            if (!isDead && worker.getLastHeartbeat() != null) {
                long elapsedMs = Duration.between(worker.getLastHeartbeat(), now).toMillis();
                if (elapsedMs > heartbeatTimeoutMs) {
                    timedOut = true;
                }
            }

            if (isDead || timedOut) {
                if (!isDead) {
                    worker.markUnresponsive();
                }

                String activeJobId = worker.getCurrentJobId();
                if (activeJobId == null) {
                    // Check if any RUNNING job is assigned to this worker
                    activeJobId = jobQueueManager.getAllJobs().stream()
                            .filter(j -> j.getState() == JobState.RUNNING && worker.getId().equals(j.getAssignedWorkerId()))
                            .map(Job::getId)
                            .findFirst()
                            .orElse(null);
                }

                if (activeJobId != null) {
                    log.warn("Watchdog detected {} worker [{}] executing job [{}]. Initiating recovery...",
                            isDead ? "dead" : "unresponsive", worker.getId(), activeJobId);

                    totalFailuresDetected.incrementAndGet();
                    boolean recovered = recoverJobFromWorker(worker, activeJobId, timedOut ? "Heartbeat timeout" : "Worker crash");
                    if (recovered) {
                        triggeredRecovery = true;
                        totalJobsRecovered.incrementAndGet();
                    }
                }
            }
        }

        // 2. Scan Running Jobs for expired leases (safety net)
        for (Job job : jobQueueManager.getAllJobs()) {
            if (job.getState() == JobState.RUNNING) {
                Instant leaseExpiry = job.getLeaseExpiresAt();
                if (leaseExpiry != null && leaseExpiry.isBefore(now)) {
                    log.warn("Watchdog detected expired lease for job [{}] (Assigned to: {}). Reclaiming...",
                            job.getId(), job.getAssignedWorkerId());

                    totalFailuresDetected.incrementAndGet();
                    boolean recovered = recoverExpiredJob(job, "Lease expired without heartbeat extension");
                    if (recovered) {
                        triggeredRecovery = true;
                        totalJobsRecovered.incrementAndGet();
                    }
                }
            }
        }

        // 3. Trigger immediate dispatch cycle if any jobs were recovered back into the queue
        if (triggeredRecovery && dispatchCoordinator != null) {
            dispatchCoordinator.dispatchCycle();
        }
    }

    private boolean recoverJobFromWorker(Worker worker, String jobId, String reason) {
        Job job = jobQueueManager.getJob(jobId).orElse(null);
        if (job == null) return false;

        String staleToken = job.getAttemptToken();
        worker.releaseJob(false);

        return processJobReassignment(job, worker.getId(), staleToken, reason);
    }

    private boolean recoverExpiredJob(Job job, String reason) {
        String workerId = job.getAssignedWorkerId();
        String staleToken = job.getAttemptToken();

        if (workerId != null) {
            workerPool.getWorkerModel(workerId).ifPresent(w -> w.releaseJob(false));
        }

        return processJobReassignment(job, workerId, staleToken, reason);
    }

    private boolean processJobReassignment(Job job, String workerId, String staleToken, String reason) {
        synchronized (job) {
            if (job.getState() != JobState.RUNNING) {
                return false;
            }

            auditService.recordJobAttemptAbandoned(job, workerId, staleToken, reason);

            job.incrementRetryCount();
            if (job.canRetry()) {
                job.markQueued();
                log.info("Job [{}] recovered and placed back in QUEUE (Retry {}/{})",
                        job.getId(), job.getRetryCount(), job.getMaxRetries());
                auditService.recordEvent("JOB_REASSIGNED", job.getId(),
                        "Job re-queued for retry " + job.getRetryCount() + "/" + job.getMaxRetries(), reason);
                return true;
            } else {
                job.markFailed("Max retries exceeded after worker failure: " + reason);
                log.error("Job [{}] exhausted retries ({}/{}). Marked FAILED.",
                        job.getId(), job.getRetryCount(), job.getMaxRetries());
                auditService.recordJobAttemptFailed(job, staleToken, "Max retries exceeded: " + reason);
                return false;
            }
        }
    }

    public int getTotalFailuresDetected() {
        return totalFailuresDetected.get();
    }

    public int getTotalJobsRecovered() {
        return totalJobsRecovered.get();
    }

    public void setHeartbeatTimeoutMs(long heartbeatTimeoutMs) {
        this.heartbeatTimeoutMs = heartbeatTimeoutMs;
    }

    public void setWatchdogIntervalMs(long watchdogIntervalMs) {
        this.watchdogIntervalMs = watchdogIntervalMs;
    }
}
