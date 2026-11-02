package com.queuepilot.coordinator.attempt;

import com.queuepilot.core.job.AttemptState;

import java.time.Instant;

public record AttemptView(String id, String jobId, String workerId, int attemptNumber, AttemptState state,
                          Instant startedAt, Instant leaseExpiresAt, Instant lastHeartbeatAt, Instant finishedAt,
                          String errorCode, String errorMessage) {

    public static AttemptView from(AttemptEntity a) {
        return new AttemptView(a.getId(), a.getJobId(), a.getWorkerId(), a.getAttemptNumber(), a.getState(),
                a.getStartedAt(), a.getLeaseExpiresAt(), a.getLastHeartbeatAt(), a.getFinishedAt(),
                a.getErrorCode(), a.getErrorMessage());
    }
}
