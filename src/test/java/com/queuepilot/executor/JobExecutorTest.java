package com.queuepilot.executor;

import com.queuepilot.model.JobResult;
import com.queuepilot.model.MathComputeJob;
import com.queuepilot.model.SortingJob;
import com.queuepilot.model.TextAnalysisJob;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class JobExecutorTest {

    @Test
    @DisplayName("SortingJobExecutor should correctly sort data and emit progress/heartbeats")
    void testSortingExecutor() throws Exception {
        SortingJobExecutor executor = new SortingJobExecutor();
        SortingJob job = new SortingJob("SortTest", 5, 100L, List.of(9, 3, 7, 1, 5, 8), "QUICKSORT");

        List<Integer> recordedProgress = new ArrayList<>();
        AtomicInteger heartbeatCount = new AtomicInteger();

        JobResult result = executor.execute(
                job,
                (percent, msg) -> recordedProgress.add(percent),
                heartbeatCount::incrementAndGet
        );

        assertTrue(result.isSuccess());
        assertTrue(heartbeatCount.get() >= 10, "Should emit heartbeats across steps");
        assertTrue(recordedProgress.contains(100), "Should report 100% completion");
        assertEquals(1, result.getOutputData().get("minElement"));
        assertEquals(9, result.getOutputData().get("maxElement"));
    }

    @Test
    @DisplayName("TextAnalysisJobExecutor should count word frequencies accurately")
    void testTextAnalysisExecutor() throws Exception {
        TextAnalysisJobExecutor executor = new TextAnalysisJobExecutor();
        String text = "QueuePilot scheduler dispatches jobs to worker threads. A resilient worker handles jobs.";
        TextAnalysisJob job = new TextAnalysisJob("TextTest", 5, 100L, text, List.of("worker", "jobs"), "WORD_FREQUENCY");

        AtomicInteger heartbeats = new AtomicInteger();
        JobResult result = executor.execute(
                job,
                (p, m) -> {},
                heartbeats::incrementAndGet
        );

        assertTrue(result.isSuccess());
        @SuppressWarnings("unchecked")
        var freq = (java.util.Map<String, Integer>) result.getOutputData().get("keywordFrequencies");
        assertEquals(2, freq.get("worker"));
        assertEquals(2, freq.get("jobs"));
    }

    @Test
    @DisplayName("MathComputeJobExecutor should approximate Pi using Monte Carlo")
    void testMathExecutorMonteCarlo() throws Exception {
        MathComputeJobExecutor executor = new MathComputeJobExecutor();
        MathComputeJob job = new MathComputeJob("PiTest", 5, 100L, "MONTE_CARLO_PI", 50_000L);

        JobResult result = executor.execute(job, (p, m) -> {}, () -> {});
        assertTrue(result.isSuccess());
        double pi = (double) result.getOutputData().get("estimatedPi");
        assertTrue(pi > 3.0 && pi < 3.3, "Approximated Pi should be close to 3.14159: " + pi);
    }

    @Test
    @DisplayName("Executor should respond immediately to thread interruption for failure simulation")
    void testInterruptionHandling() {
        SortingJobExecutor executor = new SortingJobExecutor();
        SortingJob job = new SortingJob("SlowSort", 5, 5000L, List.of(5, 4, 3, 2, 1), "QUICKSORT");

        AtomicBoolean wasInterrupted = new AtomicBoolean(false);
        Thread workerThread = new Thread(() -> {
            try {
                executor.execute(job, (p, m) -> {}, () -> {});
            } catch (InterruptedException e) {
                wasInterrupted.set(true);
            } catch (Exception ignored) {
            }
        });

        workerThread.start();
        try {
            Thread.sleep(50);
            workerThread.interrupt();
            workerThread.join(1000);
        } catch (InterruptedException ignored) {}

        assertTrue(wasInterrupted.get(), "Thread interruption should throw InterruptedException");
    }
}
