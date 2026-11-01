package com.queuepilot.benchmark;

import com.queuepilot.dispatch.DispatchCoordinator;
import com.queuepilot.dispatch.JobQueueManager;
import com.queuepilot.model.Job;
import com.queuepilot.model.MathComputeJob;
import com.queuepilot.model.SortingJob;
import com.queuepilot.model.TextAnalysisJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Pre-packages the standard 10-job benchmark workload.
 * Enforces the 10-30 second duration requirement for student-professor live demonstrations.
 * Demonstrates Demo Scenario Step 1 ("Submit 10 jobs").
 */
@Service
public class DemoWorkloadService {

    private static final Logger log = LoggerFactory.getLogger(DemoWorkloadService.class);

    private final JobQueueManager jobQueueManager;
    private final DispatchCoordinator dispatchCoordinator;

    @Autowired
    public DemoWorkloadService(JobQueueManager jobQueueManager, DispatchCoordinator dispatchCoordinator) {
        this.jobQueueManager = jobQueueManager;
        this.dispatchCoordinator = dispatchCoordinator;
    }

    /**
     * Creates and submits the 10 demonstrable jobs (10 to 30 seconds each).
     */
    public List<Job> seedDemoWorkload() {
        log.info("Seeding standard 10-job demonstration workload (10s to 30s each)...");

        List<Job> jobs = createStandard10Jobs();
        for (Job job : jobs) {
            jobQueueManager.submitJob(job);
        }

        // Trigger initial dispatch
        dispatchCoordinator.dispatchCycle();

        log.info("Successfully seeded and queued 10 demonstration jobs.");
        return jobs;
    }

    /**
     * Generates a repeatable list of 10 jobs.
     */
    public static List<Job> createStandard10Jobs() {
        List<Job> jobs = new ArrayList<>();

        // 1. Heavy sorting task (25s)
        Job job1 = new SortingJob("Sort-25k-QuickSort", 8, 25_000L,
                SortingJob.generateSampleData(25_000), "QUICKSORT");
        jobs.add(job1);

        // 2. Text NLP corpus extraction (20s)
        Job job2 = new TextAnalysisJob("Text-NLP-WordFreq", 6, 20_000L,
                null, List.of("queue", "pilot", "worker", "resilient"), "WORD_FREQUENCY");
        jobs.add(job2);

        // 3. Monte Carlo Pi simulation (30s)
        Job job3 = new MathComputeJob("Math-MonteCarloPi-HighRes", 10, 30_000L,
                "MONTE_CARLO_PI", 100_000_000L);
        jobs.add(job3);

        // 4. MergeSort dataset (15s)
        Job job4 = new SortingJob("Sort-15k-MergeSort", 4, 15_000L,
                SortingJob.generateSampleData(15_000), "MERGESORT");
        jobs.add(job4);

        // 5. Keyword search in text (12s)
        Job job5 = new TextAnalysisJob("Text-Keyword-Scan", 5, 12_000L,
                null, List.of("scheduler", "thread", "lease"), "KEYWORD_COUNT");
        jobs.add(job5);

        // 6. Prime factorization (18s)
        Job job6 = new MathComputeJob("Math-PrimeFactorization", 7, 18_000L,
                "PRIME_FACTORIZATION", 982_451_653L);
        jobs.add(job6);

        // 7. QuickSort short task (10s)
        Job job7 = new SortingJob("Sort-10k-QuickSort-Fast", 3, 10_000L,
                SortingJob.generateSampleData(10_000), "QUICKSORT");
        jobs.add(job7);

        // 8. Text Sentiment & Tokenizer (14s)
        Job job8 = new TextAnalysisJob("Text-Tokenize-Analysis", 5, 14_000L,
                null, List.of("platform", "academic", "prototype"), "WORD_FREQUENCY");
        jobs.add(job8);

        // 9. Monte Carlo Pi medium (16s)
        Job job9 = new MathComputeJob("Math-MonteCarloPi-Mid", 6, 16_000L,
                "MONTE_CARLO_PI", 50_000_000L);
        jobs.add(job9);

        // 10. Dependent batch verification job (12s, depends on Job 1)
        Job job10 = new SortingJob("Sort-PostVerification", 9, 12_000L,
                SortingJob.generateSampleData(12_000), "QUICKSORT");
        job10.addDependency(job1.getId());
        jobs.add(job10);

        return jobs;
    }
}
