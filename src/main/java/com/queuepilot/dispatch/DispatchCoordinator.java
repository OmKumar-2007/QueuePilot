package com.queuepilot.dispatch;

import com.queuepilot.model.Job;
import com.queuepilot.model.JobResult;
import com.queuepilot.model.Worker;
import com.queuepilot.scheduler.SchedulerPolicyFactory;
import com.queuepilot.scheduler.SchedulingPolicy;
import com.queuepilot.worker.JobAssignment;
import com.queuepilot.worker.WorkerEventListener;
import com.queuepilot.worker.WorkerInstance;
import com.queuepilot.worker.WorkerPool;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The central scheduling coordinator that bridges the shared Job Queue,
 * the active Scheduling Policy, and the concurrent Worker Pool.
 * Satisfies SRS F4, F5, F7, U3.
 */
@Component
public class DispatchCoordinator implements WorkerEventListener, Runnable {

    private static final Logger log = LoggerFactory.getLogger(DispatchCoordinator.class);

    private final JobQueueManager jobQueueManager;
    private final WorkerPool workerPool;
    private final SchedulerPolicyFactory policyFactory;

    @Value("${queuepilot.workers.lease-duration-ms:5000}")
    private long leaseDurationMs = 5000;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread coordinatorThread;

    @Autowired
    public DispatchCoordinator(JobQueueManager jobQueueManager,
                               WorkerPool workerPool,
                               SchedulerPolicyFactory policyFactory) {
        this.jobQueueManager = jobQueueManager;
        this.workerPool = workerPool;
        this.policyFactory = policyFactory;
        this.workerPool.setEventListener(this);
    }

    @PostConstruct
    public synchronized void start() {
        if (!running.get()) {
            running.set(true);
            coordinatorThread = new Thread(this, "dispatch-coordinator-thread");
            coordinatorThread.setDaemon(true);
            coordinatorThread.start();
            log.info("DispatchCoordinator started with lease duration {}ms.", leaseDurationMs);
        }
    }

    @PreDestroy
    public synchronized void stop() {
        running.set(false);
        if (coordinatorThread != null) {
            coordinatorThread.interrupt();
        }
    }

    @Override
    public void run() {
        while (running.get()) {
            try {
                dispatchCycle();
                Thread.sleep(200);
            } catch (InterruptedException e) {
                if (!running.get()) {
                    break;
                }
            } catch (Exception e) {
                log.error("Error in dispatch cycle: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * Executes one scheduling and dispatch cycle.
     * Can also be called on-demand when jobs are submitted or completed.
     */
    public synchronized void dispatchCycle() {
        List<WorkerInstance> idleWorkers = workerPool.getAvailableWorkers();
        if (idleWorkers.isEmpty()) {
            return;
        }

        List<Job> runnableJobs = jobQueueManager.getRunnableJobs();
        if (runnableJobs.isEmpty()) {
            return;
        }

        Instant now = Instant.now();
        SchedulingPolicy activePolicy = policyFactory.getActivePolicy();
        List<Job> candidateJobs = new ArrayList<>(runnableJobs);

        for (WorkerInstance workerInstance : idleWorkers) {
            if (candidateJobs.isEmpty()) {
                break;
            }

            Worker workerModel = workerInstance.getWorkerModel();
            Optional<Job> matchedJob = activePolicy.selectNextJob(candidateJobs, workerModel, now);

            if (matchedJob.isPresent()) {
                Job jobToDispatch = matchedJob.get();
                Optional<String> tokenOpt = jobQueueManager.claimJob(jobToDispatch, workerModel, leaseDurationMs);

                if (tokenOpt.isPresent()) {
                    String attemptToken = tokenOpt.get();
                    JobAssignment assignment = new JobAssignment(
                            jobToDispatch,
                            attemptToken,
                            Instant.now().plusMillis(leaseDurationMs)
                    );

                    boolean accepted = workerInstance.assign(assignment);
                    if (accepted) {
                        log.info("Dispatched job [{}] to worker [{}] using policy [{}]",
                                jobToDispatch.getId(), workerModel.getId(), activePolicy.getPolicyName());
                        candidateJobs.remove(jobToDispatch);
                    } else {
                        log.warn("Worker [{}] failed to accept assignment for job [{}]", workerModel.getId(), jobToDispatch.getId());
                    }
                }
            }
        }
    }

    // --- WorkerEventListener Callbacks ---

    @Override
    public void onProgress(String workerId, String jobId, String attemptToken, int progressPercent, String message) {
        jobQueueManager.updateProgress(jobId, attemptToken, progressPercent, message);
    }

    @Override
    public void onHeartbeat(String workerId, String jobId, String attemptToken) {
        workerPool.getWorkerModel(workerId).ifPresent(Worker::recordHeartbeat);
        if (jobId != null && attemptToken != null) {
            jobQueueManager.renewLease(jobId, attemptToken, leaseDurationMs);
        }
    }

    @Override
    public void onJobCompleted(String workerId, String jobId, String attemptToken, JobResult result) {
        jobQueueManager.completeJob(jobId, attemptToken, result);
        // Worker is now free, trigger dispatch for pending jobs immediately
        dispatchCycle();
    }

    @Override
    public void onJobFailed(String workerId, String jobId, String attemptToken, String reason, Throwable cause) {
        jobQueueManager.failJob(jobId, attemptToken, reason);
        // Trigger dispatch to re-evaluate queue
        dispatchCycle();
    }

    public void setLeaseDurationMs(long leaseDurationMs) {
        this.leaseDurationMs = leaseDurationMs;
    }

    public long getLeaseDurationMs() {
        return leaseDurationMs;
    }
}
