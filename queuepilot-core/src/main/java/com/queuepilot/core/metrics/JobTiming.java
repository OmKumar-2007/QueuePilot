package com.queuepilot.core.metrics;

import java.time.Duration;
import java.time.Instant;

/** The three timestamps every scheduling metric is derived from. */
public record JobTiming(Instant submittedAt, Instant startedAt, Instant finishedAt) {

    public long waitMs() {
        return Duration.between(submittedAt, startedAt).toMillis();
    }

    public long turnaroundMs() {
        return Duration.between(submittedAt, finishedAt).toMillis();
    }

    public long executionMs() {
        return Duration.between(startedAt, finishedAt).toMillis();
    }
}
