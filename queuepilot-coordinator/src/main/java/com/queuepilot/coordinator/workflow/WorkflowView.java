package com.queuepilot.coordinator.workflow;

import com.queuepilot.core.job.JobState;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** A workflow with its graph (nodes = jobs, edges = prerequisite -> dependant) and aggregate progress. */
public record WorkflowView(String id,
                           String name,
                           Instant createdAt,
                           String state,
                           int percentComplete,
                           Map<JobState, Integer> stateCounts,
                           List<Node> nodes,
                           List<Edge> edges) {

    public record Node(String jobId, String step, String task, JobState state, int progressPercent) {
    }

    public record Edge(String from, String to) {
    }

    /** RUNNING while anything is unfinished; afterwards SUCCEEDED only if every step succeeded. */
    static String overallState(Map<JobState, Integer> counts, int total) {
        int succeeded = counts.getOrDefault(JobState.SUCCEEDED, 0);
        int finished = 0;
        for (Map.Entry<JobState, Integer> entry : counts.entrySet()) {
            if (entry.getKey().isTerminal()) {
                finished += entry.getValue();
            }
        }
        if (finished < total) {
            return counts.getOrDefault(JobState.RUNNING, 0) > 0 || finished > 0 ? "RUNNING" : "PENDING";
        }
        return succeeded == total ? "SUCCEEDED" : "FAILED";
    }
}
