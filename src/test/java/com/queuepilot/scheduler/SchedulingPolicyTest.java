package com.queuepilot.scheduler;

import com.queuepilot.model.Job;
import com.queuepilot.model.JobType;
import com.queuepilot.model.SortingJob;
import com.queuepilot.model.TextAnalysisJob;
import com.queuepilot.model.Worker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SchedulingPolicyTest {

    private FcFSSchedulingPolicy fcfs;
    private SjfSchedulingPolicy sjf;
    private PriorityAgingSchedulingPolicy priorityAging;

    @BeforeEach
    void setUp() {
        fcfs = new FcFSSchedulingPolicy();
        sjf = new SjfSchedulingPolicy();
        priorityAging = new PriorityAgingSchedulingPolicy(2, 2); // age every 2 seconds by +2
    }

    @Test
    @DisplayName("FCFS should prioritize jobs in strict chronological arrival order")
    void testFcfsOrdering() throws InterruptedException {
        Job job1 = new SortingJob("Job-1", 5, 20_000, List.of(3, 1, 2), "QUICKSORT");
        Thread.sleep(10);
        Job job2 = new SortingJob("Job-2", 10, 5_000, List.of(5, 4), "QUICKSORT");

        List<Job> ordered = fcfs.orderJobs(List.of(job2, job1), Instant.now());
        assertEquals("Job-1", ordered.get(0).getName(), "First arrived job must be scheduled first");
        assertEquals("Job-2", ordered.get(1).getName());
    }

    @Test
    @DisplayName("SJF should select jobs with shortest estimated duration first")
    void testSjfOrdering() {
        Job longJob = new SortingJob("Long-Job", 10, 30_000, List.of(1), "QUICKSORT");
        Job mediumJob = new SortingJob("Medium-Job", 5, 15_000, List.of(1), "QUICKSORT");
        Job shortJob = new SortingJob("Short-Job", 1, 5_000, List.of(1), "QUICKSORT");

        List<Job> ordered = sjf.orderJobs(List.of(longJob, shortJob, mediumJob), Instant.now());
        assertEquals("Short-Job", ordered.get(0).getName());
        assertEquals("Medium-Job", ordered.get(1).getName());
        assertEquals("Long-Job", ordered.get(2).getName());
    }

    @Test
    @DisplayName("Priority Aging should elevate low-priority jobs that wait in queue")
    void testPriorityAging() {
        Instant t0 = Instant.now();
        Job lowPriorityOldJob = new SortingJob("Low-Old", 1, 10_000, List.of(1), "QUICKSORT", t0);

        // A new high priority job arrives at t0 + 6 seconds
        Instant t6 = t0.plusSeconds(6);
        Job highPriorityNewJob = new SortingJob("High-New", 5, 10_000, List.of(1), "QUICKSORT", t6);

        // At t0 (before aging), Low-Old priority is 1, High-New is 5
        assertEquals(1, lowPriorityOldJob.getEffectivePriority());

        // At t6: Low-Old has waited 6s. agingInterval=2s, increment=2 -> boost = (6/2)*2 = 6
        // Low-Old effective priority becomes 1 + 6 = 7.
        // High-New has waited 0s -> effective priority remains 5.
        List<Job> orderedAtT6 = priorityAging.orderJobs(List.of(lowPriorityOldJob, highPriorityNewJob), t6);
        assertEquals("Low-Old", orderedAtT6.get(0).getName(), "At t6, aged job should surpass newer high priority job");
        assertEquals(7, lowPriorityOldJob.getEffectivePriority());
        assertEquals(5, highPriorityNewJob.getEffectivePriority());
    }

    @Test
    @DisplayName("Worker capability matching should only dispatch compatible jobs")
    void testCapabilityMatching() {
        Worker sortWorker = new Worker("w1", "Sorting-Specialist", Set.of(JobType.SORTING.getDefaultCapabilityTag()));
        
        Job textJob = new TextAnalysisJob("Text-Task", 10, 5000, "hello world", List.of("hello"), "WORD_FREQUENCY");
        Job sortJob = new SortingJob("Sort-Task", 1, 10000, List.of(2, 1), "QUICKSORT");

        Optional<Job> selected = fcfs.selectNextJob(List.of(textJob, sortJob), sortWorker, Instant.now());

        assertTrue(selected.isPresent());
        assertEquals("Sort-Task", selected.get().getName(), "Worker should only select job it has capabilities for");
    }
}
