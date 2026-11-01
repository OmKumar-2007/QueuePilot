package com.queuepilot.dispatch;

import com.queuepilot.executor.ExecutorRegistry;
import com.queuepilot.model.Job;
import com.queuepilot.model.JobState;
import com.queuepilot.model.MathComputeJob;
import com.queuepilot.model.SortingJob;
import com.queuepilot.model.TextAnalysisJob;
import com.queuepilot.scheduler.SchedulerPolicyFactory;
import com.queuepilot.worker.WorkerPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class DispatchCoordinatorTest {

    private JobQueueManager queueManager;
    private WorkerPool workerPool;
    private SchedulerPolicyFactory policyFactory;
    private DispatchCoordinator coordinator;

    @BeforeEach
    void setUp() {
        queueManager = new JobQueueManager();
        workerPool = new WorkerPool(new ExecutorRegistry());
        workerPool.initializeDefaultPool();
        policyFactory = new SchedulerPolicyFactory();

        coordinator = new DispatchCoordinator(queueManager, workerPool, policyFactory);
        coordinator.start();
    }

    @AfterEach
    void tearDown() {
        coordinator.stop();
        workerPool.stopAll();
    }

    @Test
    @DisplayName("Should execute 4 concurrent jobs simultaneously across 4 workers")
    void testFourJobsConcurrentExecution() throws InterruptedException {
        Job job1 = new SortingJob("Sort-1", 5, 200L, List.of(3, 2, 1), "QUICKSORT");
        Job job2 = new TextAnalysisJob("Text-1", 5, 200L, "hello world queue pilot", List.of("hello"), "WORD_FREQUENCY");
        Job job3 = new MathComputeJob("Math-1", 5, 200L, "MONTE_CARLO_PI", 5_000L);
        Job job4 = new SortingJob("Sort-2", 5, 200L, List.of(9, 8, 7), "QUICKSORT");

        queueManager.submitJob(job1);
        queueManager.submitJob(job2);
        queueManager.submitJob(job3);
        queueManager.submitJob(job4);

        coordinator.dispatchCycle();

        // Wait up to 3 seconds for all 4 jobs to complete
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            boolean allDone = job1.getState() == JobState.COMPLETED &&
                              job2.getState() == JobState.COMPLETED &&
                              job3.getState() == JobState.COMPLETED &&
                              job4.getState() == JobState.COMPLETED;
            if (allDone) break;
            Thread.sleep(100);
        }

        assertEquals(JobState.COMPLETED, job1.getState());
        assertEquals(JobState.COMPLETED, job2.getState());
        assertEquals(JobState.COMPLETED, job3.getState());
        assertEquals(JobState.COMPLETED, job4.getState());

        assertNotNull(job1.getResult());
        assertNotNull(job2.getResult());
        assertNotNull(job3.getResult());
        assertNotNull(job4.getResult());
    }

    @Test
    @DisplayName("Dependent job should remain BLOCKED and automatically execute after prerequisite completes")
    void testDagAutomaticUnblocking() throws InterruptedException {
        Job prereq = new SortingJob("Prereq-Job", 5, 150L, List.of(2, 1), "QUICKSORT");
        Job dependent = new TextAnalysisJob("Dependent-Job", 5, 150L, "analysis after sorting", List.of("after"), "WORD_FREQUENCY");

        dependent.addDependency(prereq.getId());

        queueManager.submitJob(prereq);
        queueManager.submitJob(dependent);

        assertEquals(JobState.QUEUED, prereq.getState());
        assertEquals(JobState.BLOCKED, dependent.getState());

        coordinator.dispatchCycle();

        // Wait for prerequisite to finish
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            if (dependent.getState() == JobState.COMPLETED) break;
            Thread.sleep(100);
        }

        assertEquals(JobState.COMPLETED, prereq.getState(), "Prerequisite must be completed");
        assertEquals(JobState.COMPLETED, dependent.getState(), "Dependent job must be unblocked and completed");
    }
}
