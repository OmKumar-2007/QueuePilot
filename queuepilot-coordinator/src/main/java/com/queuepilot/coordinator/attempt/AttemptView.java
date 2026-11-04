package com.queuepilot.coordinator.attempt;

import com.queuepilot.core.job.AttemptState;

import java.time.Instant;

public record AttemptView(String id, String jobId, String workerId, int attemptNumber, AttemptState state,
                          Instant startedAt, Instant leaseExpiresAt, Instant finishedAt, String error) {

    public static AttemptView from(AttemptEntity a) {
        return new AttemptView(a.getId(), a.getJobId(), a.getWorkerId(), a.getAttemptNumber(), a.getState(),
                a.getStartedAt(), a.getLeaseExpiresAt(), a.getFinishedAt(), a.getError());
    }
}
