package com.queuepilot.core.scheduling;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Round-robin fairness across logical queues (F14).
 *
 * <p>Jobs are grouped by queue; queues are visited in name order starting with the queue after
 * the one served last; inside each queue the active policy decides the order. The result is one
 * flat candidate list: the scheduler claims the first candidate that a worker can take and then
 * calls {@link #markServed} so the next decision starts with the following queue.</p>
 */
public class QueueRotation {

    private String lastServedQueue;

    public synchronized List<SchedulableJob> arrange(Collection<SchedulableJob> jobs, SchedulingPolicy policy, Instant now) {
        Map<String, List<SchedulableJob>> byQueue = new TreeMap<>();
        for (SchedulableJob job : jobs) {
            byQueue.computeIfAbsent(job.queue(), queue -> new ArrayList<>()).add(job);
        }

        List<SchedulableJob> arranged = new ArrayList<>(jobs.size());
        for (String queue : rotationOrder(new ArrayList<>(byQueue.keySet()))) {
            arranged.addAll(policy.order(byQueue.get(queue), now));
        }
        return arranged;
    }

    public synchronized void markServed(String queue) {
        lastServedQueue = queue;
    }

    public synchronized String lastServedQueue() {
        return lastServedQueue;
    }

    /** Sorted queue names rotated so that the first queue after {@code lastServedQueue} comes first. */
    private List<String> rotationOrder(List<String> sortedQueues) {
        if (lastServedQueue == null) {
            return sortedQueues;
        }
        int start = 0;
        while (start < sortedQueues.size() && sortedQueues.get(start).compareTo(lastServedQueue) <= 0) {
            start++;
        }
        List<String> rotated = new ArrayList<>(sortedQueues.subList(start, sortedQueues.size()));
        rotated.addAll(sortedQueues.subList(0, start));
        return rotated;
    }
}
