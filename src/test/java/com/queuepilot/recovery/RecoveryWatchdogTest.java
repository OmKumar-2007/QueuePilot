package com.queuepilot.recovery;

import com.queuepilot.dispatch.DispatchCoordinator;
import com.queuepilot.dispatch.JobQueueManager;
import com.queuepilot.executor.ExecutorRegistry;
import com.queuepilot.model.Job;
import com.queuepilot.model.JobState;
import com.queuepilot.model.SortingJob;
import com.queuepilot.persistence.AuditService;
import com.queuepilot.scheduler.SchedulerPolicyFactory;
import com.queuepilot.worker.WorkerPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RecoveryWatchdogTest {

    private JobQueueManager queueManager;
    private WorkerPool workerPool;
    private SchedulerPolicyFactory policyFactory;
    private DispatchCoordinator coordinator;
    private AuditService auditService;
    private RecoveryWatchdog watchdog;

    @BeforeEach
    void setUp() {
        auditService = new AuditService(); // In-memory mock
        queueManager = new JobQueueManager(auditService);
        workerPool = new WorkerPool(new ExecutorRegistry());
        workerPool.initializeDefaultPool();
        policyFactory = new SchedulerPolicyFactory();

        coordinator = new DispatchCoordinator(queueManager, workerPool, policyFactory);
        coordinator.start();

        watchdog = new RecoveryWatchdog(workerPool, queueManager, auditService, coordinator);
        watchdog.setHeartbeatTimeoutMs(300); // Fast timeout for unit testing
    }

    @AfterEach
    void tearDown() {
        watchdog.stop();
        coordinator.stop();
        workerPool.stopAll();
    }

    @Test
    @DisplayName("Watchdog should detect crashed worker, reclaim job, and reassign to another worker")
    void testCrashDetectionAndAutomaticReassignment() throws InterruptedException {
        // Submit a job with 2000ms duration
        SortingJob job = new SortingJob("Recoverable-Job", 5, 2000L, List.of(5, 4, 3, 2, 1), "QUICKSORT");
        queueManager.submitJob(job);
        coordinator.dispatchCycle();

        // Wait until a worker picks it up
        Thread.sleep(100);
        assertEquals(JobState.RUNNING, job.getState());
        String initialWorkerId = job.getAssignedWorkerId();
        assertNotNull(initialWorkerId);

        // Simulate crash of the worker executing the job (Demo Step 5)
        workerPool.simulateCrash(initialWorkerId);

        // Run watchdog scan (Demo Step 6: Failure detection and recovery)
        watchdog.checkAndRecover();

        assertTrue(watchdog.getTotalFailuresDetected() >= 1, "Watchdog should detect the failed worker");
        assertEquals(1, job.getRetryCount(), "Retry count should be incremented");

        // Give coordinator a moment to reassign to another healthy worker
        coordinator.dispatchCycle();

        // Wait for reassigned job to complete on another worker
        long deadline = System.currentTimeMillis() + 4000;
        while (System.currentTimeMillis() < deadline) {
            if (job.getState() == JobState.COMPLETED) break;
            Thread.sleep(100);
        }

        assertEquals(JobState.COMPLETED, job.getState(), "Job must complete after reassignment to another worker");
        assertNotEquals(initialWorkerId, job.getAssignedWorkerId(), "Job should have been reassigned to a different worker");
    }

    @Test
    @DisplayName("Watchdog should mark job FAILED when retry limit is exhausted")
    void testRetryExhaustion() throws InterruptedException {
        SortingJob job = new SortingJob("Failing-Job", 5, 2000L, List.of(2, 1), "QUICKSORT");
        job.setMaxRetries(1); // Only 1 attempt permitted
        queueManager.submitJob(job);
        coordinator.dispatchCycle();

        Thread.sleep(100);
        assertEquals(JobState.RUNNING, job.getState());

        // Crash worker
        workerPool.simulateCrash(job.getAssignedWorkerId());

        // Run recovery
        watchdog.checkAndRecover();

        assertEquals(JobState.FAILED, job.getState(), "Job must transition to FAILED when max retries is exhausted");
        assertTrue(job.getFailureReason().contains("Max retries exceeded"));
    }
}
