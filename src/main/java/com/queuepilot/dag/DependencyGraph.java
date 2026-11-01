package com.queuepilot.dag;

import com.queuepilot.model.Job;
import com.queuepilot.model.JobState;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Directed Acyclic Graph (DAG) manager for job dependency tracking, cycle detection,
 * and readiness validation.
 */
public class DependencyGraph {

    // jobId -> Set of prerequisite job IDs that must complete first
    private final Map<String, Set<String>> adjacencyList = new HashMap<>();

    public synchronized void addJob(Job job) {
        if (job == null) return;
        adjacencyList.computeIfAbsent(job.getId(), k -> new HashSet<>());
        for (String depId : job.getDependencies()) {
            adjacencyList.get(job.getId()).add(depId);
        }
    }

    public synchronized void addDependency(String dependentJobId, String prerequisiteJobId) {
        if (dependentJobId.equals(prerequisiteJobId)) {
            throw new CycleDetectedException("Self dependency detected for job: " + dependentJobId,
                    List.of(dependentJobId, prerequisiteJobId));
        }
        adjacencyList.computeIfAbsent(dependentJobId, k -> new HashSet<>()).add(prerequisiteJobId);
        adjacencyList.computeIfAbsent(prerequisiteJobId, k -> new HashSet<>());

        // Verify cycle immediately
        validateNoCycles();
    }

    /**
     * Cycle detection using DFS with 3-color marking:
     * 0 = UNVISITED, 1 = VISITING (currently in recursion stack), 2 = VISITED (completed).
     */
    public synchronized void validateNoCycles() throws CycleDetectedException {
        Map<String, Integer> state = new HashMap<>(); // 0=unvisited, 1=visiting, 2=visited
        List<String> path = new ArrayList<>();

        for (String node : adjacencyList.keySet()) {
            if (state.getOrDefault(node, 0) == 0) {
                if (dfsHasCycle(node, state, path)) {
                    throw new CycleDetectedException("Circular dependency detected in job dependency graph!", path);
                }
            }
        }
    }

    private boolean dfsHasCycle(String current, Map<String, Integer> state, List<String> path) {
        state.put(current, 1);
        path.add(current);

        Set<String> dependencies = adjacencyList.getOrDefault(current, Collections.emptySet());
        for (String dep : dependencies) {
            int depState = state.getOrDefault(dep, 0);
            if (depState == 1) {
                // Found cycle!
                path.add(dep);
                return true;
            }
            if (depState == 0) {
                if (dfsHasCycle(dep, state, path)) {
                    return true;
                }
            }
        }

        path.remove(path.size() - 1);
        state.put(current, 2);
        return false;
    }

    /**
     * Determines whether a given job is ready to run:
     * 1. Scheduled start time has passed (or is null).
     * 2. All prerequisite jobs have reached JobState.COMPLETED.
     */
    public synchronized boolean isJobRunnable(Job job, Map<String, Job> allJobs, Instant now) {
        if (job == null) return false;

        // Check scheduled start time
        if (job.getScheduledStartTime() != null && job.getScheduledStartTime().isAfter(now)) {
            return false;
        }

        // Check prerequisites
        Set<String> prerequisites = adjacencyList.getOrDefault(job.getId(), job.getDependencies());
        for (String prereqId : prerequisites) {
            Job prereqJob = allJobs.get(prereqId);
            if (prereqJob == null || prereqJob.getState() != JobState.COMPLETED) {
                return false;
            }
        }

        return true;
    }

    public synchronized Set<String> getPrerequisites(String jobId) {
        return Collections.unmodifiableSet(adjacencyList.getOrDefault(jobId, Collections.emptySet()));
    }

    public synchronized void removeJob(String jobId) {
        adjacencyList.remove(jobId);
        for (Set<String> deps : adjacencyList.values()) {
            deps.remove(jobId);
        }
    }

    public synchronized void clear() {
        adjacencyList.clear();
    }
}
