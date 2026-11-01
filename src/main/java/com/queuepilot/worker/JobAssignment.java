package com.queuepilot.worker;

import com.queuepilot.model.Job;

import java.time.Instant;
import java.util.Objects;

/**
 * Represents an active dispatch lease binding a Job to a Worker attempt.
 */
public record JobAssignment(Job job, String attemptToken, Instant leaseExpiresAt) {
    public JobAssignment {
        Objects.requireNonNull(job, "Job cannot be null");
        Objects.requireNonNull(attemptToken, "Attempt token cannot be null");
        Objects.requireNonNull(leaseExpiresAt, "Lease expiry cannot be null");
    }
}
