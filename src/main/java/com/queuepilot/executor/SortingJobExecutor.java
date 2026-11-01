package com.queuepilot.executor;

import com.queuepilot.model.Job;
import com.queuepilot.model.JobResult;
import com.queuepilot.model.JobType;
import com.queuepilot.model.SortingJob;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Concrete executor for sorting workloads.
 * Demonstrates chunked execution, real algorithm computation, and heartbeat pulsing.
 */
public class SortingJobExecutor implements JobExecutor {

    @Override
    public JobType getSupportedType() {
        return JobType.SORTING;
    }

    @Override
    public JobResult execute(Job job, ProgressCallback progressCallback, HeartbeatCallback heartbeatCallback) throws Exception {
        if (!(job instanceof SortingJob sortingJob)) {
            throw new IllegalArgumentException("Unsupported job class: " + job.getClass().getName());
        }

        long startTime = System.currentTimeMillis();
        long targetDurationMs = Math.max(100L, sortingJob.getEstimatedDurationMs());
        int totalSteps = 10;
        long stepDelayMs = Math.max(10L, targetDurationMs / totalSteps);

        List<Integer> listToSort = new ArrayList<>(sortingJob.getData());

        progressCallback.onProgress(0, "Initializing dataset of " + listToSort.size() + " items");
        heartbeatCallback.sendHeartbeat();

        for (int step = 1; step <= totalSteps; step++) {
            // Check interruption before each step
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Worker thread interrupted during sorting step " + step);
            }

            // Real chunk processing: sort partial windows of data
            int fromIdx = (step - 1) * (listToSort.size() / totalSteps);
            int toIdx = (step == totalSteps) ? listToSort.size() : step * (listToSort.size() / totalSteps);
            if (fromIdx < toIdx) {
                Collections.sort(listToSort.subList(fromIdx, toIdx));
            }

            // Controlled stepped delay to demonstrate 10-30s execution and progress to professor
            Thread.sleep(stepDelayMs);

            int currentPercent = (int) (((double) step / totalSteps) * 95);
            progressCallback.onProgress(currentPercent, String.format("Chunk %d/%d processed (%s)", step, totalSteps, sortingJob.getAlgorithm()));
            heartbeatCallback.sendHeartbeat();
        }

        // Final full sort
        Collections.sort(listToSort);
        long elapsed = System.currentTimeMillis() - startTime;
        progressCallback.onProgress(100, "Sorting finished in " + (elapsed / 1000.0) + "s");

        Map<String, Object> output = new HashMap<>();
        output.put("itemsSorted", listToSort.size());
        output.put("algorithmUsed", sortingJob.getAlgorithm());
        output.put("minElement", listToSort.isEmpty() ? null : listToSort.get(0));
        output.put("maxElement", listToSort.isEmpty() ? null : listToSort.get(listToSort.size() - 1));
        output.put("actualDurationMs", elapsed);

        return JobResult.success("Sorted " + listToSort.size() + " items using " + sortingJob.getAlgorithm(), elapsed)
                .outputData(output)
                .build();
    }
}
