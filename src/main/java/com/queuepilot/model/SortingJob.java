package com.queuepilot.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Concrete Job subclass for numerical and structured data sorting.
 */
public class SortingJob extends Job {

    private final List<Integer> data;
    private final String algorithm;

    public SortingJob(String name, int priority, long estimatedDurationMs, List<Integer> data, String algorithm) {
        this(name, priority, estimatedDurationMs, data, algorithm, java.time.Instant.now());
    }

    public SortingJob(String name, int priority, long estimatedDurationMs, List<Integer> data, String algorithm, java.time.Instant createdAt) {
        super(name, JobType.SORTING, priority, estimatedDurationMs, createdAt);
        this.data = (data != null && !data.isEmpty()) ? new ArrayList<>(data) : generateSampleData(10_000);
        this.algorithm = (algorithm != null && !algorithm.isBlank()) ? algorithm.toUpperCase() : "QUICKSORT";
    }

    public static List<Integer> generateSampleData(int size) {
        Random rand = new Random(42);
        List<Integer> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(rand.nextInt(100_000));
        }
        return list;
    }

    public List<Integer> getData() {
        return Collections.unmodifiableList(data);
    }

    public String getAlgorithm() {
        return algorithm;
    }

    @Override
    public String getSummary() {
        return String.format("Sort %d integers using %s (est: %.1fs)", 
                data.size(), algorithm, getEstimatedDurationMs() / 1000.0);
    }

    @Override
    public Object getPayload() {
        return String.format("%d items, algo=%s", data.size(), algorithm);
    }
}
