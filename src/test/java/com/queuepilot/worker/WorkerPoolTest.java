package com.queuepilot.worker;

import com.queuepilot.executor.ExecutorRegistry;
import com.queuepilot.model.JobResult;
import com.queuepilot.model.SortingJob;
import com.queuepilot.model.WorkerStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class WorkerPoolTest {

    private WorkerPool workerPool;
    private ExecutorRegistry executorRegistry;

    @BeforeEach
    void setUp() {
        executorRegistry = new ExecutorRegistry();
        workerPool = new WorkerPool(executorRegistry);
        workerPool.initializeDefaultPool();
    }

    @AfterEach
    void tearDown() {
        workerPool.stopAll();
    }

    @Test
    @DisplayName("WorkerPool should initialize 4 distinct workers with proper capabilities")
    void testPoolInitialization() {
        assertEquals(4, workerPool.getAllWorkerInstances().size());
        assertTrue(workerPool.getWorkerModel("worker-1").isPresent());
        assertTrue(workerPool.getWorkerModel("worker-2").isPresent());
        assertTrue(workerPool.getWorkerModel("worker-3").isPresent());
        assertTrue(workerPool.getWorkerModel("worker-4").isPresent());
    }

    @Test
    @DisplayName("WorkerInstance should execute job and notify listener upon completion")
    void testWorkerExecution() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean completed = new AtomicBoolean(false);

        workerPool.setEventListener(new WorkerEventListener() {
            @Override
            public void onProgress(String workerId, String jobId, String attemptToken, int progressPercent, String message) {}

            @Override
            public void onHeartbeat(String workerId, String jobId, String attemptToken) {}

            @Override
            public void onJobCompleted(String workerId, String jobId, String attemptToken, JobResult result) {
                completed.set(true);
                latch.countDown();
            }

            @Override
            public void onJobFailed(String workerId, String jobId, String attemptToken, String reason, Throwable cause) {}
        });

        WorkerInstance worker = workerPool.getWorkerInstance("worker-1").orElseThrow();
        SortingJob job = new SortingJob("QuickSort", 5, 100L, List.of(3, 2, 1), "QUICKSORT");

        JobAssignment assignment = new JobAssignment(job, "token-1", Instant.now().plusSeconds(5));
        boolean assigned = worker.assign(assignment);
        assertTrue(assigned, "Worker should accept assignment");

        boolean finishedInTime = latch.await(2, TimeUnit.SECONDS);
        assertTrue(finishedInTime, "Job should complete within 2 seconds");
        assertTrue(completed.get());
        assertEquals(WorkerStatus.IDLE, worker.getWorkerModel().getStatus(), "Worker should return to IDLE");
    }

    @Test
    @DisplayName("Simulate crash should terminate worker thread and mark status DEAD")
    void testSimulateCrashAndRevive() throws InterruptedException {
        WorkerInstance worker = workerPool.getWorkerInstance("worker-2").orElseThrow();
        SortingJob longJob = new SortingJob("LongSort", 5, 5000L, List.of(5, 4, 3, 2, 1), "QUICKSORT");

        JobAssignment assignment = new JobAssignment(longJob, "token-crash", Instant.now().plusSeconds(10));
        worker.assign(assignment);

        Thread.sleep(50);
        workerPool.simulateCrash("worker-2");

        assertEquals(WorkerStatus.DEAD, worker.getWorkerModel().getStatus());

        // Revive worker
        workerPool.reviveWorker("worker-2");
        assertEquals(WorkerStatus.IDLE, worker.getWorkerModel().getStatus());
        assertTrue(worker.isIdle(), "Revived worker should be idle and ready for tasks");
    }
}
