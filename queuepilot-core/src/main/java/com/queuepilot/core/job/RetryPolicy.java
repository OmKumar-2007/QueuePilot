package com.queuepilot.core.job;

import java.time.Duration;

/**
 * Bounded retry with exponential backoff: delay = base * 2^(retryNumber - 1), capped at max.
 *
 * @param baseDelay delay before the first retry
 * @param maxDelay  upper bound for any delay
 */
public record RetryPolicy(Duration baseDelay, Duration maxDelay) {

    public RetryPolicy {
        if (baseDelay.isNegative() || maxDelay.compareTo(baseDelay) < 0) {
            throw new IllegalArgumentException("Require 0 <= baseDelay <= maxDelay");
        }
    }

    /**
     * Whether another attempt is allowed. {@code attemptsUsed} counts attempts already started,
     * so with {@code maxRetries = 3} a job may run at most 4 times.
     */
    public static boolean canRetry(int attemptsUsed, int maxRetries) {
        return attemptsUsed <= maxRetries;
    }

    /** Delay before retry number {@code retryNumber} (1 = first retry). */
    public Duration delayBefore(int retryNumber) {
        int exponent = Math.max(0, Math.min(retryNumber - 1, 20));
        long delayMs = baseDelay.toMillis() * (1L << exponent);
        return Duration.ofMillis(Math.min(delayMs, maxDelay.toMillis()));
    }
}
