package com.queuepilot.dag;

import com.queuepilot.model.Job;
import com.queuepilot.model.JobResult;
import com.queuepilot.model.JobState;
import com.queuepilot.model.SortingJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DependencyGraphTest {

    private DependencyGraph dag;

    @BeforeEach
    void setUp() {
        dag = new DependencyGraph();
    }

    @Test
    @DisplayName("Self dependency should be rejected immediately")
    void testSelfDependencyCycle() {
        assertThrows(CycleDetectedException.class, () -> {
            dag.addDependency("job-A", "job-A");
        });
    }

    @Test
    @DisplayName("Indirect circular dependency A -> B -> C -> A should throw CycleDetectedException")
    void testIndirectCycleDetection() {
        dag.addDependency("job-B", "job-A"); // B depends on A
        dag.addDependency("job-C", "job-B"); // C depends on B

        // Closing the cycle: A depends on C
        assertThrows(CycleDetectedException.class, () -> {
            dag.addDependency("job-A", "job-C");
        });
    }

    @Test
    @DisplayName("Valid DAG should permit execution only after prerequisites succeed")
    void testPrerequisiteReadiness() {
        Job jobA = new SortingJob("Job-A", 5, 1000, List.of(1), "QUICKSORT");
        Job jobB = new SortingJob("Job-B", 5, 1000, List.of(1), "QUICKSORT");

        jobB.addDependency(jobA.getId());
        dag.addJob(jobA);
        dag.addJob(jobB);

        Map<String, Job> registry = new HashMap<>();
        registry.put(jobA.getId(), jobA);
        registry.put(jobB.getId(), jobB);

        Instant now = Instant.now();

        // Initially Job-A is runnable (no prerequisites)
        assertTrue(dag.isJobRunnable(jobA, registry, now));
        // Job-B is blocked waiting for Job-A
        assertFalse(dag.isJobRunnable(jobB, registry, now));

        // When Job-A completes
        jobA.markCompleted(JobResult.success("Done", 100).build());

        // Now Job-B becomes runnable!
        assertTrue(dag.isJobRunnable(jobB, registry, now));
    }
}
