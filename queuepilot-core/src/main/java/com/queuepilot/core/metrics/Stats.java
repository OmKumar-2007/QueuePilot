package com.queuepilot.core.metrics;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/** Summary statistics of a sample (median and p95 use nearest-rank percentiles). */
public record Stats(int count, double mean, double median, double p95, double min, double max) {

    public static Stats of(Collection<? extends Number> values) {
        if (values.isEmpty()) {
            return new Stats(0, 0, 0, 0, 0, 0);
        }
        List<Double> sorted = new ArrayList<>();
        double sum = 0;
        for (Number value : values) {
            sorted.add(value.doubleValue());
            sum += value.doubleValue();
        }
        Collections.sort(sorted);
        return new Stats(sorted.size(), sum / sorted.size(), percentile(sorted, 50), percentile(sorted, 95),
                sorted.get(0), sorted.get(sorted.size() - 1));
    }

    /** Nearest-rank percentile of an already sorted list. */
    public static double percentile(List<Double> sorted, double percent) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int rank = (int) Math.ceil(percent / 100.0 * sorted.size());
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, rank - 1)));
    }
}
