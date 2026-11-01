package com.queuepilot.worker;

import com.queuepilot.executor.ExecutorRegistry;
import com.queuepilot.executor.JobExecutor;
import com.queuepilot.model.Job;
import com.queuepilot.model.JobResult;
import com.queuepilot.model.Worker;
import com.queuepilot.model.WorkerStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Encapsulates a physical concurrent worker thread and its execution lifecycle.
 * Supports dynamic assignment, progress streaming, heartbeat emission,
 * and live fault simulation (crash and unresponsiveness).
 */
public class WorkerInstance implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(WorkerInstance.class);

    private final Worker workerModel;
    private final ExecutorRegistry executorRegistry;
    private volatile WorkerEventListener listener;

    private final BlockingQueue<JobAssignment> assignmentInbox = new LinkedBlockingQueue<>(1);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean frozen = new AtomicBoolean(false);

    private Thread executionThread;
    private volatile JobAssignment currentAssignment;

    public WorkerInstance(Worker workerModel, ExecutorRegistry executorRegistry, WorkerEventListener listener) {
        this.workerModel = workerModel;
        this.executorRegistry = executorRegistry;
        this.listener = listener;
    }

    public void setEventListener(WorkerEventListener listener) {
        this.listener = listener;
    }

    public synchronized void start() {
        if (!running.get()) {
            running.set(true);
            executionThread = new Thread(this, "worker-thread-" + workerModel.getId());
            executionThread.setDaemon(true);
            executionThread.start();
            log.info("Worker [{}] thread started.", workerModel.getId());
        }
    }

    public synchronized void stop() {
        running.set(false);
        if (executionThread != null) {
            executionThread.interrupt();
        }
    }

    /**
     * Attempts to assign a job to this worker.
     * Returns true if accepted into inbox, false if worker is not idle or inbox full.
     */
    public synchronized boolean assign(JobAssignment assignment) {
        if (!workerModel.getStatus().isHealthy() || currentAssignment != null) {
            return false;
        }
        boolean offered = assignmentInbox.offer(assignment);
        if (offered) {
            this.currentAssignment = assignment;
            this.workerModel.assignJob(assignment.job().getId());
        }
        return offered;
    }

    @Override
    public void run() {
        while (running.get()) {
            try {
                JobAssignment assignment = currentAssignment;
                if (assignment == null) {
                    assignment = assignmentInbox.poll(500, TimeUnit.MILLISECONDS);
                    if (assignment == null) {
                        // Emit idle heartbeat periodically
                        if (!frozen.get()) {
                            workerModel.recordHeartbeat();
                            if (listener != null) {
                                listener.onHeartbeat(workerModel.getId(), null, null);
                            }
                        }
                        continue;
                    }
                    this.currentAssignment = assignment;
                }

                executeAssignment(assignment);

            } catch (InterruptedException e) {
                if (!running.get() || workerModel.getStatus() == WorkerStatus.DEAD) {
                    log.info("Worker [{}] thread interrupted and stopping.", workerModel.getId());
                    break;
                }
                log.warn("Worker [{}] thread interrupted while waiting for assignment.", workerModel.getId());
            } catch (Exception e) {
                log.error("Unexpected error in worker loop [{}]: {}", workerModel.getId(), e.getMessage(), e);
            } finally {
                this.currentAssignment = null;
            }
        }
    }

    private void executeAssignment(JobAssignment assignment) {
        Job job = assignment.job();
        String token = assignment.attemptToken();
        log.info("Worker [{}] began execution of job [{}] with token [{}]", workerModel.getId(), job.getId(), token);

        try {
            JobExecutor executor = executorRegistry.getExecutor(job.getType())
                    .orElseThrow(() -> new IllegalStateException("No executor registered for type " + job.getType()));

            JobResult result = executor.execute(
                    job,
                    (progressPercent, stepDesc) -> {
                        if (!frozen.get()) {
                            workerModel.recordHeartbeat();
                            if (listener != null) {
                                listener.onProgress(workerModel.getId(), job.getId(), token, progressPercent, stepDesc);
                            }
                        }
                    },
                    () -> {
                        // Heartbeat callback
                        if (!frozen.get()) {
                            workerModel.recordHeartbeat();
                            if (listener != null) {
                                listener.onHeartbeat(workerModel.getId(), job.getId(), token);
                            }
                        } else {
                            log.debug("Worker [{}] is FROZEN: suppressing heartbeat for job [{}]", workerModel.getId(), job.getId());
                        }
                    }
            );

            // Job finished successfully
            workerModel.releaseJob(true);
            if (listener != null) {
                listener.onJobCompleted(workerModel.getId(), job.getId(), token, result);
            }
            log.info("Worker [{}] completed job [{}] successfully in {}ms", workerModel.getId(), job.getId(), result.getExecutionDurationMs());

        } catch (InterruptedException e) {
            log.warn("Worker [{}] was interrupted/killed during job [{}] execution", workerModel.getId(), job.getId());
            workerModel.markDead();
            // In crash/failure simulation, the worker terminates silently.
            // The RecoveryWatchdog detects the dead/unresponsive worker and reclaims the job.
        } catch (Exception e) {
            log.error("Worker [{}] encountered execution error for job [{}]: {}", workerModel.getId(), job.getId(), e.getMessage());
            workerModel.releaseJob(false);
            if (listener != null) {
                listener.onJobFailed(workerModel.getId(), job.getId(), token, e.getMessage(), e);
            }
        }
    }

    /**
     * Simulates worker failure / crash (interruption).
     */
    public synchronized void simulateCrash() {
        log.warn("Simulating crash for worker [{}]", workerModel.getId());
        running.set(false);
        workerModel.markDead();
        if (executionThread != null && executionThread.isAlive()) {
            executionThread.interrupt();
            try {
                executionThread.join(300);
            } catch (InterruptedException ignored) {}
        }
    }

    /**
     * Simulates worker freeze (unresponsive, stops emitting heartbeats).
     */
    public synchronized void setFrozen(boolean freeze) {
        this.frozen.set(freeze);
        if (freeze) {
            log.warn("Worker [{}] FROZEN - heartbeats suspended to trigger lease timeout.", workerModel.getId());
        } else {
            log.info("Worker [{}] UNFROZEN - resumed heartbeats.", workerModel.getId());
            workerModel.recordHeartbeat();
        }
    }

    /**
     * Revives a dead or crashed worker thread.
     */
    public synchronized void revive() {
        log.info("Reviving worker [{}]", workerModel.getId());
        if (executionThread != null && executionThread.isAlive()) {
            executionThread.interrupt();
            try {
                executionThread.join(1000);
            } catch (InterruptedException ignored) {}
        }
        this.frozen.set(false);
        this.currentAssignment = null;
        this.assignmentInbox.clear();
        this.workerModel.markAlive();
        start();
    }

    public Worker getWorkerModel() {
        return workerModel;
    }

    public boolean isIdle() {
        return workerModel.getStatus() == WorkerStatus.IDLE && currentAssignment == null;
    }

    public boolean isFrozen() {
        return frozen.get();
    }

    public JobAssignment getCurrentAssignment() {
        return currentAssignment;
    }
}
